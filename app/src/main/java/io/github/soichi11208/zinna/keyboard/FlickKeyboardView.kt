package io.github.soichi11208.zinna.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.soichi11208.zinna.theme.KeyboardTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a [KeyboardLayout] and turns touches into [KeyOutput]s.
 *
 * Flick resolution is deliberately handled here rather than delegated to mozc's toggle table: we
 * know the pointer geometry, so we can commit the moment the finger crosses the threshold and give
 * the user a live guide of where each direction leads. mozc then receives an already-resolved kana.
 *
 * Multi-touch is supported to the extent that matters for typing speed — a second finger landing
 * while the first is still down commits the first key immediately, which is what fast thumb typists
 * expect from Simeji and Gboard.
 */
class FlickKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface OnKeyOutputListener {
        fun onKeyOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection)
    }

    var listener: OnKeyOutputListener? = null

    var theme: KeyboardTheme = KeyboardTheme.Default
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    /** What to draw while a finger rests on a flick key. See [FlickGuideStyle]. */
    var guideStyle: FlickGuideStyle = FlickGuideStyle.PREVIEW
        set(value) {
            field = value
            invalidate()
        }

    /** Reused so the preview costs no allocation on a path that redraws as the finger moves. */
    private val previewRect = RectF()

    /** Whether the next letter, or every letter, is capitalised. See [KeyAction.Shift]. */
    private enum class ShiftState { OFF, ONCE, LOCKED }

    private var shift = ShiftState.OFF

    var layout: KeyboardLayout? = null
        set(value) {
            field = value
            // A plane switch starts fresh: a shift armed on the symbol page means nothing on the
            // one being switched to, and leaving it latched is the kind of state users cannot see
            // the cause of.
            shift = ShiftState.OFF
            // Recompute here rather than waiting for onSizeChanged: every plane has the same row
            // count, so swapping kana → ascii leaves the view exactly the same size and
            // onSizeChanged never fires. Relying on it left the new layout with no placed keys at
            // all — a blank keyboard that ignores touches.
            activeTouches.clear()
            cancelRepeat()
            refreshPlacement()
            requestLayout()
            invalidate()
        }

    /**
     * Distance in dp the finger must travel before a flick registers. Exposed because thumb size
     * and screen density vary enough that a single value annoys somebody; the settings screen maps
     * a slider onto it.
     */
    var flickThresholdDp: Float = DEFAULT_FLICK_THRESHOLD_DP

    /**
     * Pixels above this view that the flick guide may draw into — in practice the height of the
     * candidate strip sitting directly above. Requires the parent to have `clipChildren = false`,
     * otherwise the guide is still cut at this view's top edge.
     */
    var guideOverflowTop: Float = 0f

    /**
     * ⌫ を長押ししたとき、キーの背景にゲージがたまり、満タンで全削除する。
     * false のときは従来どおり長押しで1文字ずつ連続削除する。
     */
    var holdToClear: Boolean = true

    /** ゲージが空から満タンになるまでの時間（ミリ秒）。設定で変えられる。 */
    var gaugeFillMs: Long = 1000L

    /** ゲージが満タンになったときに呼ばれる（全削除）。 */
    var clearAllListener: (() -> Unit)? = null

    /** 「☺記」を長押ししたときに呼ばれる（メニューを開く）。 */
    var panelLongPressListener: (() -> Unit)? = null

    private var longPressTouch: Touch? = null
    private val longPressRunnable = Runnable {
        val touch = longPressTouch ?: return@Runnable
        longPressTouch = null
        touch.firedOnPress = true // 指を離しても普通のタップとして扱わない
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        activeTouches.values.remove(touch)
        invalidate()
        panelLongPressListener?.invoke()
    }

    private fun cancelPanelLongPress() {
        longPressTouch = null
        handler.removeCallbacks(longPressRunnable)
    }

    /** ゲージを表示中の ⌫ キー。null ならゲージなし。 */
    private var gaugeKey: PlacedKey? = null
    private var gaugeStartedAt = 0L
    private val gaugePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gaugeTicker = object : Runnable {
        override fun run() {
            val key = gaugeKey ?: return
            if (gaugeProgress() >= 1f) {
                gaugeKey = null
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                clearAllListener?.invoke()
                playClearWave()
                invalidate()
                return
            }
            invalidate(key.bounds.left.toInt(), key.bounds.top.toInt(),
                key.bounds.right.toInt() + 1, key.bounds.bottom.toInt() + 1)
            handler.postDelayed(this, 16)
        }
    }

    private fun gaugeProgress(): Float {
        val elapsed = SystemClock.uptimeMillis() - gaugeStartedAt - GAUGE_DELAY_MS
        return (elapsed.toFloat() / gaugeFillMs).coerceIn(0f, 1f)
    }

    private fun startGauge(key: PlacedKey) {
        gaugeKey = key
        gaugeStartedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(gaugeTicker)
        handler.postDelayed(gaugeTicker, GAUGE_DELAY_MS)
    }

    private fun cancelGauge() {
        if (gaugeKey == null) return
        gaugeKey = null
        handler.removeCallbacks(gaugeTicker)
        invalidate()
    }

    private data class PlacedKey(val spec: KeySpec, val bounds: RectF)

    /** State for one finger currently on the keyboard. */
    private class Touch(
        val key: PlacedKey,
        val downX: Float,
        val downY: Float,
        var direction: FlickDirection = FlickDirection.CENTER,
        /** Set for repeatable keys, which emit on press instead of on release. */
        var firedOnPress: Boolean = false,
    ) {
        /** 今の指の位置（リキッドグラスの光と伸びに使う）。 */
        var x: Float = downX
        var y: Float = downY
        val downAt: Long = SystemClock.uptimeMillis()
    }

    /** 指を離したあと、元の形に戻っていく途中のキー。 */
    private class Releasing(val key: PlacedKey, val dx: Float, val dy: Float, val x: Float, val y: Float, val at: Long)
    private val releasing = ArrayList<Releasing>()

    private var placedKeys: List<PlacedKey> = emptyList()
    private val activeTouches = mutableMapOf<Int, Touch>()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modifierPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val guideBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guideLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private val flickThresholdPx: Float
        get() = flickThresholdDp * resources.displayMetrics.density

    init {
        isFocusable = false
        isClickable = true
        applyTheme()
    }

    private fun applyTheme() {
        keyPaint.color = theme.keyColor
        keyPressedPaint.color = theme.keyPressedColor
        modifierPaint.color = theme.modifierKeyColor
        labelPaint.color = theme.labelColor
        guideBackgroundPaint.color = theme.flickGuideColor
        guideLabelPaint.color = theme.flickGuideLabelColor
        gaugePaint.color = GAUGE_COLOR
        glassShader = null
        // The parent paints the panel — a colour, or the user's background image. Painting it here
        // too would cover that image with an opaque rectangle.
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowCount = layout?.rows?.size ?: 4
        val height = (theme.keyHeightDp * resources.displayMetrics.density * rowCount).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        labelPaint.textSize = theme.labelSizeSp * resources.displayMetrics.scaledDensity
        guideLabelPaint.textSize = theme.labelSizeSp * resources.displayMetrics.scaledDensity
        refreshPlacement()
    }

    private fun refreshPlacement() {
        placedKeys = if (width > 0 && height > 0) {
            computePlacement(width.toFloat(), height.toFloat())
        } else {
            emptyList()
        }
    }

    // ------------------------------------------------------------ 片手モード
    //
    //   右手モード                    左手モード
    //   ┌───┬────────────┐        ┌────────────┬───┐
    //   │ ⇄ │  キーボード │        │  キーボード │ ⇄ │
    //   │ ⤢ │  （少し狭く）│        │  （少し狭く）│ ⤢ │
    //   └───┴────────────┘        └────────────┴───┘
    //   ⇄ = 反対側へ寄せる   ⤢ = 片手モードをやめる

    /** 0 = ふつう、-1 = 左に寄せる、1 = 右に寄せる。 */
    var oneHand: Int = 0
        set(value) {
            field = value
            refreshPlacement()
            invalidate()
        }

    /** ⇄（action=1）や ⤢（action=0）が押されたときに呼ばれる。 */
    var oneHandListener: ((Int) -> Unit)? = null

    private val asideSwap = RectF()
    private val asideExit = RectF()

    private fun keyboardArea(width: Float): Pair<Float, Float> {
        if (oneHand == 0) return 0f to width
        val kw = width * ONE_HAND_RATIO
        return if (oneHand < 0) 0f to kw else (width - kw) to kw
    }

    private fun layoutAside(width: Float, height: Float) {
        if (oneHand == 0) { asideSwap.setEmpty(); asideExit.setEmpty(); return }
        val (x0, kw) = keyboardArea(width)
        val left = if (oneHand < 0) x0 + kw else 0f
        val right = left + (width - kw)
        asideSwap.set(left, 0f, right, height / 2f)
        asideExit.set(left, height / 2f, right, height)
    }

    private fun drawAside(canvas: Canvas) {
        if (oneHand == 0) return
        for ((rect, label) in listOf(asideSwap to "⇄", asideExit to "⤢")) {
            val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, labelPaint)
        }
    }

    private fun computePlacement(width: Float, height: Float): List<PlacedKey> {
        layoutAside(width, height)
        val (originX, areaWidth) = keyboardArea(width)
        return computePlacementIn(originX, areaWidth, height)
    }

    private fun computePlacementIn(originX: Float, width: Float, height: Float): List<PlacedKey> {
        val current = layout ?: return emptyList()
        val totalRowWeight = current.rows.sumOf { it.weight.toDouble() }.toFloat()
        val gap = theme.keyGapDp * resources.displayMetrics.density
        val placed = mutableListOf<PlacedKey>()
        var y = 0f
        for (row in current.rows) {
            val rowHeight = height * (row.weight / totalRowWeight)
            val rowWeight = row.totalWeight
            var x = originX + width * (row.padStart / rowWeight)
            for (key in row.keys) {
                val keyWidth = width * (key.weight / rowWeight)
                placed += PlacedKey(
                    key,
                    RectF(x + gap, y + gap, x + keyWidth - gap, y + rowHeight - gap),
                )
                x += keyWidth + width * (key.gapAfter / rowWeight)
            }
            y += rowHeight
        }
        return placed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = theme.keyCornerRadiusDp * resources.displayMetrics.density

        drawAside(canvas)
        val waveT = waveProgress()
        for (placed in placedKeys) {
            val bump = if (waveT >= 0f) waveBump(placed, waveT) else 0f
            if (bump > 0f) {
                canvas.save()
                canvas.translate(0f, -bump * WAVE_HEIGHT_DP * resources.displayMetrics.density)
            }
            drawKey(canvas, placed, radius)
            if (bump > 0f) {
                wavePaint.alpha = (bump * 60).toInt()
                canvas.drawRoundRect(placed.bounds, radius, radius, wavePaint)
                canvas.restore()
            }
        }
        if (waveT >= 0f || liquidAnimating()) postInvalidateOnAnimation()

        // Guides go on top of every key so a guide near the edge is never clipped by a neighbour.
        for (touch in activeTouches.values) {
            if (!touch.key.spec.hasFlicks) continue
            when (guideStyle) {
                FlickGuideStyle.PREVIEW ->
                    if (glass) drawLiquidPreview(canvas, touch, radius) else drawFlickPreview(canvas, touch, radius)
                FlickGuideStyle.DIRECTIONS -> drawFlickGuide(canvas, touch, radius)
                FlickGuideStyle.NONE -> Unit
            }
        }
    }

    // ------------------------------------------------------------ 全消しの波
    //
    //  全消しした瞬間、キーが右端から左端へ順番に少し浮いて沈む（約0.65秒）。
    //   右 →→→→ 左
    //   ⌫ さ か あ ↶   ← 右のキーから順に ふわっ
    private var waveStartedAt = -1L
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }

    /** 波を始める。 */
    fun playClearWave() {
        waveStartedAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** 波の進み具合 0〜1。波が出ていなければ -1。 */
    private fun waveProgress(): Float {
        if (waveStartedAt < 0) return -1f
        val t = (SystemClock.uptimeMillis() - waveStartedAt).toFloat() / WAVE_MS
        if (t >= 1f) { waveStartedAt = -1L; return -1f }
        return t
    }

    /** そのキーがどれだけ浮いているか 0〜1。右のキーほど早く動き出す。 */
    private fun waveBump(key: PlacedKey, t: Float): Float {
        val fromRight = if (width > 0) 1f - key.bounds.centerX() / width else 0f
        val u = (t - fromRight * 0.45f) / 0.55f
        if (u <= 0f || u >= 1f) return 0f
        return kotlin.math.sin(u * Math.PI).toFloat()
    }

    // ------------------------------------------------------------ リキッドグラス
    //
    //  キー1つの断面（上が明るく、下へ消えていく細い縁）
    //   ╭──────────╮ ← 縁の上側：白く少し光る
    //   │  半透明   │ ← 中：うっすら白い（背景が透ける）
    //   ╰──────────╯ ← 縁の下側：ほぼ見えない
    //  影・ぼかし・虹色などは使わない（やりすぎ防止）。

    /** リキッドグラス風に描くか。 */
    var glass: Boolean = true
        set(value) { field = value; invalidate() }

    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glassMatrix = android.graphics.Matrix()
    private var glassShader: android.graphics.LinearGradient? = null

    /** 文字が明るい＝暗いテーマ。ガラスの濃さを変えるのに使う。 */
    private val darkTheme: Boolean
        get() {
            val c = theme.labelColor
            val lum = (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) +
                0.114 * android.graphics.Color.blue(c)) / 255.0
            return lum > 0.5
        }

    private fun prepareGlass() {
        val d = resources.displayMetrics.density
        glassStroke.strokeWidth = 1f * d
        val top = if (darkTheme) 0x59 else 0xCC
        val bottom = if (darkTheme) 0x0A else 0x22
        glassShader = android.graphics.LinearGradient(
            0f, 0f, 0f, 1f,
            android.graphics.Color.argb(top, 255, 255, 255),
            android.graphics.Color.argb(bottom, 255, 255, 255),
            android.graphics.Shader.TileMode.CLAMP,
        )
        glassStroke.shader = glassShader
    }

    private val glassRect = RectF()
    private val glassPath = android.graphics.Path()
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun ease(t: Float): Float { val u = 1f - t.coerceIn(0f, 1f); return 1f - u * u * u }

    /**
     * キー1つをリキッドグラスで描く。
     *
     *  触れる  → キーがわずかにふくらみ、指の位置に光（ハイライト）が出て、指を追いかける
     *  ずらす  → キーが指の方へ液体のように少し伸びる
     *  離す    → 0.2秒かけて元の形に戻り、光が消える
     */
    private fun drawGlassKey(canvas: Canvas, placed: PlacedKey) {
        if (glassShader == null) prepareGlass()
        val d = resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        var strength = 0f
        var dx = 0f
        var dy = 0f
        var lx = 0f
        var ly = 0f
        val touch = activeTouches.values.firstOrNull { it.key === placed }
        if (touch != null) {
            strength = ease((now - touch.downAt) / PRESS_MS)
            dx = touch.x - touch.downX; dy = touch.y - touch.downY; lx = touch.x; ly = touch.y
        } else {
            val r = releasing.lastOrNull { it.key === placed }
            if (r != null) {
                strength = 1f - ease((now - r.at) / RELEASE_MS)
                dx = r.dx; dy = r.dy; lx = r.x; ly = r.y
            }
        }

        val b = placed.bounds
        glassRect.set(b)
        if (strength > 0f) {
            // ふくらみ（最大 2dp）と、指の方への伸び（最大 9dp）
            val swell = 2f * d * strength
            glassRect.inset(-swell, -swell)
            val limit = 9f * d
            val sx = (dx * 0.2f).coerceIn(-limit, limit) * strength
            val sy = (dy * 0.2f).coerceIn(-limit, limit) * strength
            if (sx > 0) glassRect.right += sx else glassRect.left += sx
            if (sy > 0) glassRect.bottom += sy else glassRect.top += sy
        }
        val r = max(theme.keyCornerRadiusDp, 11f) * d
        val character = placed.spec.style == KeyStyle.CHARACTER
        val rest = if (character) (if (darkTheme) 0x1C else 0xA6) else (if (darkTheme) 0x10 else 0x70)
        val pressed = if (darkTheme) 0x34 else 0xE6
        val alpha = (rest + (pressed - rest) * strength).toInt()
        glassFill.color = android.graphics.Color.argb(alpha, 255, 255, 255)
        canvas.drawRoundRect(glassRect, r, r, glassFill)

        if (strength > 0f) {
            // 指の位置の光。キーの形の内側だけに描く。
            glassPath.reset()
            glassPath.addRoundRect(glassRect, r, r, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(glassPath)
            val radius = max(glassRect.width(), glassRect.height()) * 0.85f
            val peak = ((if (darkTheme) 0x48 else 0x70) * strength).toInt()
            specularPaint.shader = android.graphics.RadialGradient(
                lx, ly, radius,
                intArrayOf(android.graphics.Color.argb(peak, 255, 255, 255), android.graphics.Color.argb(0, 255, 255, 255)),
                null, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(lx, ly, radius, specularPaint)
            canvas.restore()
        }

        glassMatrix.setScale(1f, glassRect.height())
        glassMatrix.postTranslate(0f, glassRect.top)
        glassShader?.setLocalMatrix(glassMatrix)
        val inset = glassStroke.strokeWidth / 2f
        canvas.drawRoundRect(glassRect.left + inset, glassRect.top + inset,
            glassRect.right - inset, glassRect.bottom - inset, r, r, glassStroke)
    }

    /** 押した直後・離した直後の動きが残っているか（残っていれば次の画面も描く）。 */
    private fun liquidAnimating(): Boolean {
        if (!glass) return false
        val now = SystemClock.uptimeMillis()
        releasing.removeAll { now - it.at > RELEASE_MS }
        return releasing.isNotEmpty() || activeTouches.values.any { now - it.downAt < PRESS_MS }
    }

    /**
     * フリック中の吹き出しを、キーから「しずく」がにゅっと伸びて離れていくように描く。
     *
     *      ╭────╮   ← 吹き出し（ガラス）
     *       ╲  ╱    ← くびれ（液体のつながり）
     *      ╭────╮   ← 押しているキー
     */
    private fun drawLiquidPreview(canvas: Canvas, touch: Touch, radius: Float) {
        val output = touch.key.spec.output(touch.direction) ?: return
        val d = resources.displayMetrics.density
        val bounds = touch.key.bounds
        val gap = 8f * d
        val grow = ease((SystemClock.uptimeMillis() - touch.downAt) / PRESS_MS)
        previewRect.set(bounds)
        previewRect.offset(0f, -(bounds.height() + gap) * grow)
        clampIntoView(previewRect)
        val r = max(theme.keyCornerRadiusDp, 11f) * d

        // くびれ：キーの上辺と吹き出しの下辺を、内側に反ったカーブでつなぐ
        val neckTop = previewRect.bottom
        val neckBottom = bounds.top
        if (neckBottom - neckTop > 2f * d && kotlin.math.abs(previewRect.centerX() - bounds.centerX()) < 1f) {
            val cx = bounds.centerX()
            val half = bounds.width() * 0.22f
            val pinch = bounds.width() * 0.09f
            val midY = (neckTop + neckBottom) / 2f
            glassPath.reset()
            glassPath.moveTo(cx - half, neckTop - r * 0.5f)
            glassPath.quadTo(cx - pinch, midY, cx - half, neckBottom + r * 0.5f)
            glassPath.lineTo(cx + half, neckBottom + r * 0.5f)
            glassPath.quadTo(cx + pinch, midY, cx + half, neckTop - r * 0.5f)
            glassPath.close()
            guideBackgroundPaint.alpha = 0xB0
            canvas.drawPath(glassPath, guideBackgroundPaint)
        }

        guideBackgroundPaint.alpha = 0xEE
        canvas.drawRoundRect(previewRect, r, r, guideBackgroundPaint)
        guideBackgroundPaint.alpha = 255
        glassMatrix.setScale(1f, previewRect.height())
        glassMatrix.postTranslate(0f, previewRect.top)
        glassShader?.setLocalMatrix(glassMatrix)
        canvas.drawRoundRect(previewRect, r, r, glassStroke)
        guideLabelPaint.color = theme.flickGuideSelectedLabelColor
        val baseline =
            previewRect.centerY() - (guideLabelPaint.descent() + guideLabelPaint.ascent()) / 2f
        canvas.drawText(output.label, previewRect.centerX(), baseline, guideLabelPaint)
    }

    private fun drawKey(canvas: Canvas, placed: PlacedKey, radius: Float) {
            // With flat keys only the pressed one gets a shape: the resting keys are their labels
            // and nothing else, so the panel (or the user's background image) shows through.
            val paint = when {
                isPressed(placed) -> keyPressedPaint
                theme.flatKeys -> null
                placed.spec.style == KeyStyle.CHARACTER -> keyPaint
                else -> modifierPaint
            }
            if (glass) drawGlassKey(canvas, placed)
            else if (paint != null) canvas.drawRoundRect(placed.bounds, radius, radius, paint)
            if (placed === gaugeKey) {
                drawGauge(canvas, placed, radius)
                val hintBaseline = placed.bounds.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                canvas.drawText("全消", placed.bounds.centerX(), hintBaseline, labelPaint)
                return
            }
            if (placed.spec.center.action is KeyAction.OpenPanel) {
                drawPanelIcon(canvas, placed.bounds)
                return
            }
            val baseline = placed.bounds.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(faceLabel(placed.spec), placed.bounds.centerX(), baseline, labelPaint)
    }

    /** ⌫ の背景に、左から右へたまっていくゲージを描く。 */
    private fun drawGauge(canvas: Canvas, key: PlacedKey, radius: Float) {
        val progress = gaugeProgress()
        if (progress <= 0f) return
        val b = key.bounds
        canvas.save()
        canvas.clipRect(b.left, b.top, b.left + b.width() * progress, b.bottom)
        canvas.drawRoundRect(b, radius, radius, gaugePaint)
        canvas.restore()
    }

    /**
     * 「☺記」キーを、絵文字ではなく線だけのにこちゃんマーク＋「記」で描く。
     * 絵文字フォントだとそこだけカラーになってしまうので、自分で線を引く。
     */
    private fun drawPanelIcon(canvas: Canvas, bounds: RectF) {
        val textWidth = labelPaint.measureText("記")
        val r = labelPaint.textSize * 0.42f
        val gap = r * 0.35f
        val total = r * 2 + gap + textWidth
        val cx = bounds.centerX() - total / 2f + r
        val cy = bounds.centerY()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelPaint.color
            style = Paint.Style.STROKE
            strokeWidth = r * 0.16f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(cx, cy, r, stroke)
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = labelPaint.color }
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.11f, eye)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.25f, r * 0.11f, eye)
        val mouth = RectF(cx - r * 0.5f, cy - r * 0.3f, cx + r * 0.5f, cy + r * 0.55f)
        canvas.drawArc(mouth, 20f, 140f, false, stroke)
        val baseline = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText("記", cx + r + gap + textWidth / 2f, baseline, labelPaint)
    }

    /**
     * A single bubble above the key showing what letting go now would type.
     *
     * The alternative — the full cross — puts a cell over each of the four neighbours, which is
     * exactly where the eye is trying to look. This shows only the answer, and follows the finger
     * as the direction changes.
     */
    private fun drawFlickPreview(canvas: Canvas, touch: Touch, radius: Float) {
        val output = touch.key.spec.output(touch.direction) ?: return
        val bounds = touch.key.bounds
        // Above the key, so the finger is not covering the very thing it is meant to read.
        previewRect.set(bounds)
        previewRect.offset(0f, -bounds.height())
        clampIntoView(previewRect)

        guideBackgroundPaint.alpha = 255
        canvas.drawRoundRect(previewRect, radius, radius, guideBackgroundPaint)
        guideLabelPaint.color = theme.flickGuideSelectedLabelColor
        val baseline =
            previewRect.centerY() - (guideLabelPaint.descent() + guideLabelPaint.ascent()) / 2f
        canvas.drawText(output.label, previewRect.centerX(), baseline, guideLabelPaint)
    }

    private fun drawFlickGuide(canvas: Canvas, touch: Touch, radius: Float) {
        val cells = guideCells(touch)
        if (cells.isEmpty()) return

        // The up-cell of a top-row key sits above this view entirely, and the side cells of the
        // outer columns sit beyond its edges. Nudging the whole cross back inside the drawable area
        // keeps every option readable; without it the top row's up-flick — う, く, す … — is simply
        // invisible, which is the one guide a user most needs to see.
        val shift = fitShift(cells.values)

        for ((direction, rect) in cells) {
            val output = touch.key.spec.output(direction) ?: continue
            rect.offset(shift.x, shift.y)
            val selected = direction == touch.direction
            guideBackgroundPaint.alpha = if (selected) 255 else 200
            canvas.drawRoundRect(rect, radius, radius, guideBackgroundPaint)
            guideLabelPaint.color =
                if (selected) theme.flickGuideSelectedLabelColor else theme.flickGuideLabelColor
            val baseline = rect.centerY() - (guideLabelPaint.descent() + guideLabelPaint.ascent()) / 2f
            canvas.drawText(output.label, rect.centerX(), baseline, guideLabelPaint)
        }
        guideBackgroundPaint.alpha = 255
    }

    private fun guideCells(touch: Touch): Map<FlickDirection, RectF> {
        val bounds = touch.key.bounds
        val w = bounds.width()
        val h = bounds.height()
        val cells = LinkedHashMap<FlickDirection, RectF>()
        for (direction in FlickDirection.entries) {
            if (touch.key.spec.output(direction) == null) continue
            val dx = when (direction) {
                FlickDirection.LEFT -> -w
                FlickDirection.RIGHT -> w
                else -> 0f
            }
            val dy = when (direction) {
                FlickDirection.UP -> -h
                FlickDirection.DOWN -> h
                else -> 0f
            }
            cells[direction] = RectF(bounds).apply { offset(dx, dy) }
        }
        return cells
    }

    /**
     * How far the guide has to move to sit inside the drawable area.
     *
     * The area extends [guideOverflowTop] above this view because the parent turns off child
     * clipping, letting the guide spill over the candidate strip rather than being cut off by it.
     */
    /** Moves [rect] the shortest distance that puts it inside the drawable area. */
    private fun clampIntoView(rect: RectF) {
        val dx = when {
            rect.left < 0f -> -rect.left
            rect.right > width -> width - rect.right
            else -> 0f
        }
        val dy = when {
            rect.top < -guideOverflowTop -> -guideOverflowTop - rect.top
            rect.bottom > height -> height - rect.bottom
            else -> 0f
        }
        rect.offset(dx, dy)
    }

    private fun fitShift(cells: Collection<RectF>): PointF {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (rect in cells) {
            left = min(left, rect.left)
            top = min(top, rect.top)
            right = max(right, rect.right)
            bottom = max(bottom, rect.bottom)
        }
        val dx = when {
            left < 0f -> -left
            right > width -> width - right
            else -> 0f
        }
        val dy = when {
            top < -guideOverflowTop -> -guideOverflowTop - top
            bottom > height -> height - bottom
            else -> 0f
        }
        return PointF(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                onPointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    onPointerMove(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                onPointerUp(event.getPointerId(index))
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPanelLongPress()
                cancelRepeat()
                cancelGauge()
                activeTouches.clear()
                invalidate()
            }
        }
        return true
    }

    private fun onPointerDown(pointerId: Int, x: Float, y: Float) {
        if (oneHand != 0) {
            if (asideSwap.contains(x, y)) { oneHandListener?.invoke(1); return }
            if (asideExit.contains(x, y)) { oneHandListener?.invoke(0); return }
        }
        val key = placedKeys.firstOrNull { it.bounds.contains(x, y) } ?: return
        // A new finger means the previous key is settled; commit it rather than letting a stray
        // move event on the old pointer rewrite it.
        activeTouches.values.forEach { commit(it) }
        cancelPanelLongPress()
        activeTouches.clear()
        cancelRepeat()
        cancelGauge()

        val touch = Touch(key, x, y)
        activeTouches[pointerId] = touch
        if (theme.hapticFeedback) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

        if (key.spec.center.action is KeyAction.OpenPanel && panelLongPressListener != null) {
            longPressTouch = touch
            handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
        }
        if (key.spec.repeatable) {
            // Repeatable keys fire on press, not on release. Backspace has to delete the moment it
            // is touched, and firing again on release would delete one extra character after every
            // hold. Character keys keep firing on release, because the flick is not resolved until
            // the finger lifts.
            touch.firedOnPress = true
            emit(touch)
            scheduleRepeat(key.spec)
        }
        invalidate()
    }

    private fun onPointerMove(pointerId: Int, x: Float, y: Float) {
        val touch = activeTouches[pointerId] ?: return
        if (holdToClear && touch.key.spec.center.action is KeyAction.Backspace) {
            // ⌫ を左へずらして、そのまま指を止めているとゲージがたまり、満タンで全削除。
            //   ┌──┐
            //   │⌫ │ ← 指を置く（長押し＝1文字ずつ連続削除）
            //   └──┘
            //  ←ずらす（連続削除は止まり、赤いゲージがたまる → 全削除）
            val dx = x - touch.downX
            val dy = y - touch.downY
            val left = dx < -flickThresholdPx && -dx > kotlin.math.abs(dy)
            if (left && gaugeKey == null) {
                cancelRepeat()
                startGauge(touch.key)
            } else if (!left && gaugeKey != null) {
                cancelGauge()
            }
            return
        }
        touch.x = x
        touch.y = y
        if (glass) invalidate() // 光と伸びが指を追いかける
        val direction = resolveDirection(touch, x, y)
        if (direction != touch.direction) {
            touch.direction = direction
            // Once the finger leaves the centre the key is no longer a hold-to-repeat candidate.
            cancelRepeat()
            cancelGauge()
            invalidate()
        }
    }

    private fun onPointerUp(pointerId: Int) {
        val touch = activeTouches.remove(pointerId) ?: return
        if (glass) releasing += Releasing(touch.key, touch.x - touch.downX, touch.y - touch.downY, touch.x, touch.y, SystemClock.uptimeMillis())
        cancelPanelLongPress()
        cancelRepeat()
        cancelGauge()
        commit(touch)
        invalidate()
    }

    private fun resolveDirection(touch: Touch, x: Float, y: Float): FlickDirection =
        FlickResolver.resolve(
            key = touch.key.spec,
            dx = x - touch.downX,
            dy = y - touch.downY,
            thresholdPx = flickThresholdPx,
        )

    private fun commit(touch: Touch) {
        if (touch.firedOnPress) return
        emit(touch)
    }

    private fun emit(touch: Touch) {
        val output = touch.key.spec.output(touch.direction) ?: return
        if (output.action is KeyAction.Shift) {
            // Tap cycles off → once → locked, the way every phone keyboard does it. Consumed here:
            // the service has nothing to do with it and never sees the key.
            shift = when (shift) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            invalidate()
            return
        }
        listener?.onKeyOutput(shiftApplied(output), touch.key.spec, touch.direction)
        if (shift == ShiftState.ONCE && output.action is KeyAction.Input) {
            shift = ShiftState.OFF
            invalidate()
        }
    }

    /**
     * Whether a finger is currently on [key].
     *
     * Scanned rather than collected into a set: onDraw runs on every touch move, and there are at
     * most a couple of fingers down, so a handful of reference comparisons beats allocating a list
     * and a set on each frame.
     */
    private fun isPressed(key: PlacedKey): Boolean {
        for (touch in activeTouches.values) {
            if (touch.key === key) return true
        }
        return false
    }

    /**
     * What to draw on a key face, which under shift is not what the layout says.
     *
     * The shift key itself reports the state rather than a fixed glyph — without that, a latched
     * shift is invisible and the next capital looks like a bug.
     */
    private fun faceLabel(spec: KeySpec): String {
        if (spec.center.action is KeyAction.Shift) {
            return when (shift) {
                ShiftState.OFF -> spec.faceLabel
                ShiftState.ONCE -> SHIFT_ONCE_LABEL
                ShiftState.LOCKED -> SHIFT_LOCKED_LABEL
            }
        }
        return if (shift == ShiftState.OFF) spec.faceLabel else spec.faceLabel.uppercase()
    }

    /** The output as typed under the current shift state. */
    private fun shiftApplied(output: KeyOutput): KeyOutput {
        if (shift == ShiftState.OFF) return output
        val action = output.action
        if (action !is KeyAction.Input) return output
        val upper = action.text.uppercase()
        if (upper == action.text) return output
        return output.copy(label = upper, action = KeyAction.Input(upper))
    }

    private fun scheduleRepeat(key: KeySpec) {
        val runnable = object : Runnable {
            override fun run() {
                listener?.onKeyOutput(key.center, key, FlickDirection.CENTER)
                if (theme.hapticFeedback) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, REPEAT_DELAY_MS)
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    override fun onDetachedFromWindow() {
        cancelRepeat()
        cancelGauge()
        super.onDetachedFromWindow()
    }

    companion object {
        const val DEFAULT_FLICK_THRESHOLD_DP = 24f

        /** 左へずらしてからゲージがたまり始めるまで。 */
        private const val GAUGE_DELAY_MS = 150L

        /** 片手モードのときのキーボードの幅（画面幅に対する割合）。 */
        private const val ONE_HAND_RATIO = 0.8f

        /** リキッドグラス：押したときにふくらむ時間と、離して戻る時間。 */
        private const val PRESS_MS = 140f
        private const val RELEASE_MS = 220f

        /** 長押しと判定するまでの時間。 */
        private const val LONG_PRESS_MS = 450L

        /** 全消しの波の長さと、浮く高さ。 */
        private const val WAVE_MS = 650f
        private const val WAVE_HEIGHT_DP = 7f

        /** ゲージの色（半透明の赤）。 */
        private val GAUGE_COLOR = android.graphics.Color.argb(150, 0xE5, 0x39, 0x35)

        /** Filled arrow while shift is armed for one letter, underlined arrow while it is locked. */
        private const val SHIFT_ONCE_LABEL = "⬆"
        private const val SHIFT_LOCKED_LABEL = "⇪"
        /** How long the key must be held before it starts repeating. */
        private const val REPEAT_DELAY_MS = 400L

        /**
         * Gap between repeats. Explicit rather than ViewConfiguration.getKeyRepeatDelay(), which
         * despite the name is the initial delay and reads far too short to use as an interval.
         *
         * Slightly slower than a hardware key repeat on purpose: every repeat on a live composition
         * costs a full mozc prediction on this thread, and typing correction made those noticeably
         * more expensive.
         */
        private const val REPEAT_INTERVAL_MS = 70L
    }
}

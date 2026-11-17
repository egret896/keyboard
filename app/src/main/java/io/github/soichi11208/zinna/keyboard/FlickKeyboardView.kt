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
    // 短いチャージ時間では待ち時間も縮める（0.01秒設定ならほぼ即時）
    private fun gaugeDelay(): Long = minOf(GAUGE_DELAY_MS, gaugeFillMs / 4)

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
        val items = touch.key.spec.popup
        if (!items.isNullOrEmpty()) {
            // 横に並べて、指をすべらせて選ぶ
            popupTouch = touch
            popupIndex = 0
            updatePopupIndex(touch.x)
            invalidate()
            return@Runnable
        }
        activeTouches.values.remove(touch)
        invalidate()
        val out = touch.key.spec.longPress ?: return@Runnable
        if (out.action is KeyAction.OpenMenu && panelLongPressListener != null) panelLongPressListener?.invoke()
        else listener?.onKeyOutput(out, touch.key.spec, FlickDirection.CENTER)
    }

    // ------------------------------------------------------------ 長押しで横に並ぶ文字
    //
    //        ┌──┬──┬──┬──┬──┐
    //        │、│。│？│！│…│  ← 指を左右にすべらせて選び、離すと入る
    //        └──┴──┴──┴──┴──┘
    //              ┌──┐
    //              │、。│ ← 長押し

    private var popupTouch: Touch? = null
    private var popupIndex = 0
    private val popupRect = RectF()
    private val popupCell = RectF()

    private fun popupLayout(touch: Touch) {
        val items = touch.key.spec.popup ?: return
        val b = touch.key.bounds
        val cellW = kotlin.math.min(b.width() * 0.62f, (width - 8f) / items.size)
        val cellH = b.height() * 0.9f
        val total = cellW * items.size
        var left = b.centerX() - cellW / 2f
        // 右にはみ出すなら、左へずらす
        if (left + total > width - 4f) left = width - 4f - total
        if (left < 4f) left = 4f
        popupRect.set(left, b.top - cellH - 4f, left + total, b.top - 4f)
    }

    private fun updatePopupIndex(x: Float) {
        val touch = popupTouch ?: return
        val items = touch.key.spec.popup ?: return
        popupLayout(touch)
        val cellW = popupRect.width() / items.size
        val i = ((x - popupRect.left) / cellW).toInt().coerceIn(0, items.size - 1)
        if (i != popupIndex) {
            popupIndex = i
            if (theme.hapticFeedback) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun drawPopup(canvas: Canvas, radius: Float) {
        val touch = popupTouch ?: return
        val items = touch.key.spec.popup ?: return
        popupLayout(touch)
        guideBackgroundPaint.alpha = 0xF0
        canvas.drawRoundRect(popupRect, radius, radius, guideBackgroundPaint)
        guideBackgroundPaint.alpha = 255
        val cellW = popupRect.width() / items.size
        items.forEachIndexed { i, s ->
            popupCell.set(popupRect.left + cellW * i, popupRect.top, popupRect.left + cellW * (i + 1), popupRect.bottom)
            if (i == popupIndex) canvas.drawRoundRect(popupCell, radius, radius, keyPressedPaint)
            guideLabelPaint.color = if (i == popupIndex) theme.flickGuideSelectedLabelColor else theme.flickGuideLabelColor
            val base = popupCell.centerY() - (guideLabelPaint.descent() + guideLabelPaint.ascent()) / 2f
            canvas.drawText(s, popupCell.centerX(), base, guideLabelPaint)
        }
        guideLabelPaint.color = theme.flickGuideLabelColor
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
                gaugeDone = true // 指を離すまで、もう一度ためない（押し続けてもアニメが繰り返さない）
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
        val elapsed = SystemClock.uptimeMillis() - gaugeStartedAt - gaugeDelay()
        return (elapsed.toFloat() / gaugeFillMs).coerceIn(0f, 1f)
    }

    /** 全削除が終わったあと、指を離すまで true。 */
    private var gaugeDone = false

    private fun startGauge(key: PlacedKey) {
        gaugeKey = key
        gaugeStartedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(gaugeTicker)
        handler.postDelayed(gaugeTicker, gaugeDelay())
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
        keyPressedPaint.color = if (pressColor != 0) pressColor else theme.keyPressedColor
        modifierPaint.color = theme.modifierKeyColor
        labelPaint.color = theme.labelColor
        val face = io.github.soichi11208.zinna.theme.Fonts.typeface(theme.keyFont, theme.keyWeight)
        labelPaint.typeface = face
        guideLabelPaint.typeface = face
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
        // 高さを指定されたら（ふつうはそう）それに従う。段数が違っても全体の高さは変えない
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec)
        else (theme.keyHeightDp * resources.displayMetrics.density * rowCount).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bevels.values.forEach { it.recycle() }
        bevels.clear()
        labelPaint.textSize = theme.labelSizeSp * resources.displayMetrics.scaledDensity
        guideLabelPaint.textSize = theme.labelSizeSp * resources.displayMetrics.scaledDensity
        refreshPlacement()
    }

    private fun refreshPlacement() {
        pressFades.clear()
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
        ensureLenses()

        drawAside(canvas)
        if (minimal && !glass) drawRules(canvas)
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
                drawGlow(canvas, placed, radius)
                canvas.restore()
            }
        }
        if (waveT >= 0f || liquidAnimating()) postInvalidateOnAnimation()

        // Guides go on top of every key so a guide near the edge is never clipped by a neighbour.
        drawPopup(canvas, radius)
        for (touch in activeTouches.values) {
            if (popupTouch === touch) continue
            if (!touch.key.spec.hasFlicks) continue
            when (guideStyle) {
                FlickGuideStyle.PREVIEW ->
                    if (memento && !glass) drawMementoPreview(canvas, touch)
                    else if (glass) drawLiquidPreview(canvas, touch, radius) else drawFlickPreview(canvas, touch, radius)
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
        // 光が右から左へ流れるのに合わせて、コツコツコツと振動も流す
        val columns = placedKeys.map { it.bounds.centerX().toInt() / 8 }.distinct().size.coerceIn(3, 10)
        haptics.wave(columns, (WAVE_MS * 0.45f / columns).toLong(), (WAVE_MS * 0.275f).toLong())
    }

    /**
     * 全消しのときにキーを光らせる。光の形はキーの見た目に合わせる。
     *   リキッドグラス＝錠剤の形、MEMENTO MORI＝傾いた札、ふつう・ミニマリスト＝「押したときの形」
     */
    private fun drawGlow(canvas: Canvas, placed: PlacedKey, radius: Float) {
        val b = placed.bounds
        when {
            memento && !glass -> {
                val d = resources.displayMetrics.density
                canvas.save()
                canvas.translate(b.centerX(), b.centerY())
                canvas.rotate(mmTilt(placed))
                mmCard(b.width() - 6f * d, b.height() - 8f * d)
                canvas.drawPath(mmPath, wavePaint)
                canvas.restore()
            }
            glass -> {
                val r = kotlin.math.min(b.width(), b.height()) / 2f
                canvas.drawRoundRect(b, r, r, wavePaint)
            }
            else -> {
                val r = when (pressShape) {
                    1 -> 0f
                    2 -> kotlin.math.min(b.width(), b.height()) / 2f
                    else -> radius
                }
                canvas.drawRoundRect(b, r, r, wavePaint)
            }
        }
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
    //  Figma の「ガラス」効果（ライト -45°）にならう。キーの角は丸めきる（錠剤の形）。
    //
    //   ✦✦╭──────────╮      ← 左上：縁の内側がふんわり白く光る（厚みのあるガラスの縁）
    //   ✦ │   くもり   │      ← 中：ほんのり白い半透明
    //     ╰──────────╯ ✦    ← 右下：反対側にも少し光が回り込む
    //
    //  光の縁は「ぼかした太い線＋細い線」を一度だけ画像に描いておき、毎回はそれを貼るだけ（軽い）。
    //  上下のグラデーションは使わない（昔のガラケーっぽくなるため）。

    /** リキッドグラス風に描くか。 */
    var glass: Boolean = true
        set(value) { field = value; invalidate() }

    // ------------------------------------------------------------ ミニマリスト
    //
    //   あ │ か │ さ        ← キーの面は描かない。キーとキーの間に細い線だけ
    //  ──     ──    ──       ← 線どうしは交わらせない（十字の所はすき間）
    //   た │ な │ は
    //
    // ------------------------------------------------------------ MEMENTO MORI
    //
    //  深い海の青。キーは斜めに傾いた札（平行四辺形）で、1枚ずつほんの少しずつ違う角度に傾く。
    //  左の縁に水色の細い線。文字は白の太い斜体。
    //  押すと：札が水色に光って少し大きく浮き上がり、白い三角のカーソルが重なる。
    //          三角に重なった部分の文字だけ赤くなる（左下に赤い小さな三角）。
    //
    /** MEMENTO MORI の見た目で描くか。 */
    var memento: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * MEMENTO MORI の種類。
     *  0 = A 海の青（青い札）　1 = B 満月（夜の黒と月の金）
     *  2 = C 大胆（白い札・大きく傾く）　3 = D 線だけ（札なし・下線と印）
     */
    var mementoStyle: Int = 0
        set(value) { field = value; invalidate() }

    private val mmAccent: Int get() = if (mementoStyle == 1) 0xFFFFE9A8.toInt() else 0xFF16CFFB.toInt()
    private val mmAccent2: Int get() = if (mementoStyle == 1) 0xFFFFF6D6.toInt() else 0xFF77FEFC.toInt()
    /** 札の傾き（斜めの度合い）。大胆は強め。 */
    private val mmSkew: Float get() = if (mementoStyle == 2) 0.4f else 0.25f

    private val mmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mmLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSkewX = -0.25f
        isFakeBoldText = true
    }
    private val mmPath = android.graphics.Path()
    private val mmMatrix = android.graphics.Matrix()

    /** キーごとに決まった、ほんの少しの傾き（-2.5°〜+2.5°）。 */
    private fun mmTilt(k: PlacedKey): Float {
        var n = (k.bounds.left * 31f + k.bounds.top * 17f).toInt() * 374761393
        n = (n xor (n ushr 13)) * 1274126177
        val amp = if (mementoStyle == 2) 9f else 5f
        return (((n xor (n ushr 16)) and 0xffff) / 65535f - 0.5f) * amp
    }

    /** 札の形（平行四辺形）を [mmPath] に作る。中心が (0,0)。 */
    private fun mmCard(w: Float, h: Float) {
        val skew = h / 2f * mmSkew        // 約14°（大胆は約22°）
        mmPath.reset()
        mmPath.moveTo(-w / 2f + skew, -h / 2f)
        mmPath.lineTo(w / 2f + skew, -h / 2f)
        mmPath.lineTo(w / 2f - skew, h / 2f)
        mmPath.lineTo(-w / 2f - skew, h / 2f)
        mmPath.close()
    }

    private fun drawMementoKey(canvas: Canvas, placed: PlacedKey, label: String?) {
        val d = resources.displayMetrics.density
        val b = placed.bounds
        val pressed = isPressed(placed)
        val character = placed.spec.style == KeyStyle.CHARACTER
        val w = b.width() - 6f * d
        val h = b.height() - 8f * d
        canvas.save()
        canvas.translate(b.centerX(), b.centerY())
        canvas.rotate(if (pressed) -4f else mmTilt(placed))
        if (pressed) canvas.scale(1.1f, 1.1f)
        mmCard(w, h)
        if (!pressed) {
            mmPaint.shader = null
            val skew = h / 2f * mmSkew
            if (mementoStyle == 3) {
                // 線だけ：下に細い白線、その左端に短い水色の印
                mmPaint.style = Paint.Style.STROKE
                mmPaint.strokeWidth = 0.6f * d
                mmPaint.color = 0x2EFFFFFF
                canvas.drawLine(-w / 2f - skew, h / 2f, w / 2f - skew, h / 2f, mmPaint)
                mmPaint.style = Paint.Style.FILL
                mmPaint.color = mmAccent
                canvas.drawRect(-w / 2f - skew, h / 2f - 0.8f * d, -w / 2f - skew + 4f * d, h / 2f + 0.2f * d, mmPaint)
            } else {
                mmPaint.color = when (mementoStyle) {
                    1 -> if (character) 0xB30A1022.toInt() else 0x1AFFE9A8
                    2 -> if (character) 0xEBFFFFFF.toInt() else 0xBF031238.toInt()
                    else -> if (character) 0x9E031238.toInt() else 0x2116CFFB
                }
                canvas.drawPath(mmPath, mmPaint)
                // 左の縁の線（大胆は太い）
                mmPaint.color = mmAccent
                mmPaint.strokeWidth = (if (mementoStyle == 2) 3.5f else 2f) * d
                mmPaint.style = Paint.Style.STROKE
                canvas.drawLine(-w / 2f + skew, -h / 2f, -w / 2f - skew, h / 2f, mmPaint)
                mmPaint.style = Paint.Style.FILL
            }
        } else {
            // 浮き上がった影＋水色の札
            canvas.save(); canvas.translate(5f * d, 5f * d)
            mmPaint.shader = null; mmPaint.color = 0x73000000
            canvas.drawPath(mmPath, mmPaint)
            canvas.restore()
            mmPaint.shader = android.graphics.LinearGradient(0f, -h / 2f, 0f, h / 2f,
                mmAccent2, mmAccent, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawPath(mmPath, mmPaint)
            mmPaint.shader = null
        }
        if (label != null) {
            mmLabel.textSize = labelPaint.textSize * (if (character) 1.12f else 0.95f)
            val baseline = -(mmLabel.descent() + mmLabel.ascent()) / 2f
            if (!pressed) {
                mmLabel.color = if (mementoStyle == 2 && character) 0xFF061A44.toInt() else android.graphics.Color.WHITE
                canvas.drawText(label, 0f, baseline, mmLabel)
            } else {
                mmLabel.color = android.graphics.Color.BLACK
                canvas.drawText(label, 0f, baseline, mmLabel)
                // 白い三角のカーソル
                val tri = android.graphics.Path().apply {
                    moveTo(-w * 0.75f, h * 0.65f); lineTo(w * 0.05f, -h * 0.75f); lineTo(w * 0.18f, h * 0.5f); close()
                }
                mmPaint.color = android.graphics.Color.WHITE
                canvas.drawPath(tri, mmPaint)
                // 三角に重なった部分の文字だけ赤く
                canvas.save(); canvas.clipPath(tri)
                mmLabel.color = 0xFFFF1E3C.toInt()
                canvas.drawText(label, 0f, baseline, mmLabel)
                canvas.restore()
                // 左下の赤い小さな三角
                val accent = android.graphics.Path().apply {
                    moveTo(-w * 0.75f, h * 0.65f); lineTo(-w * 0.55f, h * 0.2f); lineTo(-w * 0.45f, h * 0.62f); close()
                }
                mmPaint.color = 0xFFFF1E3C.toInt()
                canvas.drawPath(accent, mmPaint)
            }
        }
        canvas.restore()
    }

    /** フリック中の吹き出し：白い札に黒い斜体の文字、左下に赤い三角。 */
    private fun drawMementoPreview(canvas: Canvas, touch: Touch) {
        val output = touch.key.spec.output(touch.direction) ?: return
        val d = resources.displayMetrics.density
        val bounds = touch.key.bounds
        previewRect.set(bounds)
        previewRect.offset(0f, -bounds.height())
        clampIntoView(previewRect)
        val w = previewRect.width() - 4f * d
        val h = previewRect.height() - 6f * d
        canvas.save()
        canvas.translate(previewRect.centerX(), previewRect.centerY())
        canvas.rotate(-3f)
        mmCard(w, h)
        canvas.save(); canvas.translate(4f * d, 4f * d)
        mmPaint.shader = null; mmPaint.color = 0x80000000.toInt(); canvas.drawPath(mmPath, mmPaint)
        canvas.restore()
        mmPaint.color = android.graphics.Color.WHITE
        canvas.drawPath(mmPath, mmPaint)
        val accent = android.graphics.Path().apply {
            moveTo(-w / 2f - 2f * d, h / 2f + 2f * d); lineTo(-w / 2f + w * 0.18f, h / 2f + 2f * d); lineTo(-w / 2f + w * 0.02f, h * 0.05f); close()
        }
        mmPaint.color = 0xFFFF1E3C.toInt()
        canvas.drawPath(accent, mmPaint)
        mmLabel.textSize = guideLabelPaint.textSize * 1.1f
        mmLabel.color = android.graphics.Color.BLACK
        canvas.drawText(output.label, 0f, -(mmLabel.descent() + mmLabel.ascent()) / 2f, mmLabel)
        canvas.restore()
    }

    /** キーの振動（強さ・感じは設定で決める）。 */
    val haptics = Haptics(context)

    /** 押したときの形。0＝テーマの角丸、1＝四角、2＝丸（まるい端）。ふつう・ミニマリストで使う。 */
    var pressShape: Int = 0
        set(value) { field = value; invalidate() }

    /** 押したときの色。0 ならテーマの色。 */
    var pressColor: Int = 0
        set(value) { field = value; keyPressedPaint.color = if (value != 0) value else theme.keyPressedColor; invalidate() }

    /** 指を離したあと、色がすっと消えていく途中のキー（キー → 離した時刻）。 */
    private val pressFades = HashMap<PlacedKey, Long>()

    /**
     * 押したときの色を描く。ふわっと出て（約0.07秒）、離すとすっと消える（約0.15秒）。
     *   押す   □ → ■（少し小さい所から広がる）
     *   離す   ■ → □（薄くなって消える）
     */
    private fun drawPressed(canvas: Canvas, b: RectF, radius: Float, pressedAt: Long = 0L, fade: Float = 1f) {
        val r = when (pressShape) {
            1 -> 0f
            2 -> kotlin.math.min(b.width(), b.height()) / 2f
            else -> radius
        }
        val t = if (pressedAt > 0L) ((SystemClock.uptimeMillis() - pressedAt) / PRESS_IN_MS.toFloat()).coerceIn(0f, 1f) else 1f
        val k = ease(t)
        val alpha = (k * fade).coerceIn(0f, 1f)
        val baseAlpha = keyPressedPaint.alpha
        keyPressedPaint.alpha = (baseAlpha * alpha).toInt()
        val inset = (1f - k) * kotlin.math.min(b.width(), b.height()) * 0.08f
        pressRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        canvas.drawRoundRect(pressRect, r, r, keyPressedPaint)
        keyPressedPaint.alpha = baseAlpha
        if (t < 1f) postInvalidateOnAnimation()
    }

    private val pressRect = RectF()

    /** 押している指の、押した時刻（押していなければ 0）。 */
    private fun pressedAt(key: PlacedKey): Long {
        for (touch in activeTouches.values) if (touch.key === key) return touch.downAt
        return 0L
    }

    /** 離したあとの消えかけの色を描く。描いたら true。 */
    private fun drawPressFade(canvas: Canvas, placed: PlacedKey, radius: Float) {
        val at = pressFades[placed] ?: return
        val t = (SystemClock.uptimeMillis() - at) / PRESS_OUT_MS.toFloat()
        if (t >= 1f) { pressFades.remove(placed); return }
        drawPressed(canvas, placed.bounds, radius, 0L, 1f - t)
        postInvalidateOnAnimation()
    }

    /** ミニマリスト（罫線だけ）で描くか。リキッドグラスより優先しない（glass が true なら glass）。 */
    var minimal: Boolean = false
        set(value) { field = value; invalidate() }

    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var rulesFor: List<PlacedKey>? = null
    private val rules = ArrayList<FloatArray>()

    /** キーの間の線を、キー配置が変わったときだけ計算し直す。 */
    private fun drawRules(canvas: Canvas) {
        if (rulesFor !== placedKeys) {
            rulesFor = placedKeys
            rules.clear()
            for (k in placedKeys) {
                val b = k.bounds
                // 右どなり（同じ段で、すぐ右にあるキー）との間に縦線
                placedKeys.filter { it !== k && it.bounds.left >= b.right - 1f &&
                    it.bounds.centerY() > b.top && it.bounds.centerY() < b.bottom }
                    .minByOrNull { it.bounds.left }?.let { n ->
                        val x = (b.right + n.bounds.left) / 2f
                        val inset = b.height() * 0.24f
                        rules += floatArrayOf(x, b.top + inset, x, b.bottom - inset)
                    }
                // 下の段との間に横線。キーごとに「自分の幅」で引く。
                // （前は真下のキーを中心で探していたので、QWERTYのように段がずれていると
                //   線が切れたり、すき間に飛び出したりしていた）
                placedKeys.filter { it !== k && it.bounds.top >= b.bottom - 1f &&
                    it.bounds.right > b.left && it.bounds.left < b.right }
                    .minByOrNull { it.bounds.top }?.let { n ->
                        val y = (b.bottom + n.bounds.top) / 2f
                        val inset = min(b.width() * 0.16f, b.height() * 0.3f)
                        rules += floatArrayOf(b.left + inset, y, b.right - inset, y)
                    }
            }
        }
        val d = resources.displayMetrics.density
        rulePaint.strokeWidth = 0.8f * d
        rulePaint.color = theme.labelColor
        rulePaint.alpha = 0x38
        for (l in rules) canvas.drawLine(l[0], l[1], l[2], l[3], rulePaint)
    }

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
        // 斜め（左上→右下）の縁の光：両端だけ光り、真ん中は消える
        val peak = if (darkTheme) 0x8C else 0xE6
        val tail = peak * 6 / 10
        glassShader = android.graphics.LinearGradient(
            0f, 0f, 1f, 1f,
            intArrayOf(
                android.graphics.Color.argb(peak, 255, 255, 255),
                android.graphics.Color.argb(0, 255, 255, 255),
                android.graphics.Color.argb(0, 255, 255, 255),
                android.graphics.Color.argb(tail, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.3f, 0.7f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        glassStroke.shader = glassShader
        shadowPaint.color = android.graphics.Color.argb(0x1A, 0, 0, 0)
        bevels.values.forEach { it.recycle() }
        bevels.clear()
    }

    private val bevels = HashMap<Long, android.graphics.Bitmap>()

    // ---- 屈折（背景画像がキーの中でゆがんで見える）
    /** このビューの真後ろの背景を返す（背景画像がなければ null）。 */
    var backdropProvider: (() -> android.graphics.Bitmap?)? = null
    /** 背景が変わった回数（変わったらレンズを作り直す）。 */
    var backdropRevision: () -> Int = { 0 }
    /** 使う人が設定で変えたガラスの強さ。 */
    internal var lensParams = Lens.Params()
        set(value) { if (field != value) { field = value; invalidate() } }
    @Volatile private var lensSignature: String? = null
    /** キーごとの「ふだん」と「押している」のガラス画像。 */
    private var lenses: Map<PlacedKey, Pair<Lens.Result, Lens.Result>> = emptyMap()

    /** キーの並びか背景が変わっていたら、レンズ画像を裏で作り直す。 */
    private fun ensureLenses() {
        if (!glass) return
        val signature = "${backdropRevision()}:$left:$top:$width:$height:${System.identityHashCode(placedKeys)}:$lensParams"
        if (signature == lensSignature) return
        lensSignature = signature
        val snapshot = backdropProvider?.invoke()
        if (snapshot == null) { lenses = emptyMap(); return }
        val sw = snapshot.width
        val sh = snapshot.height
        val pixels = IntArray(sw * sh)
        snapshot.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        snapshot.recycle()
        val keys = placedKeys.map { it to RectF(it.bounds) }
        val density = resources.displayMetrics.density
        val labelLight = darkTheme
        val params = lensParams
        LENS_WORKER.execute {
            val made = HashMap<PlacedKey, Pair<Lens.Result, Lens.Result>>()
            for ((key, rect) in keys) {
                if (lensSignature != signature) return@execute
                val rest = Lens.render(pixels, sw, sh, rect, density, labelLight, 0f, params) ?: continue
                val pressed = Lens.render(pixels, sw, sh, rect, density, labelLight, 1f, params) ?: continue
                made[key] = rest to pressed
            }
            post { if (lensSignature == signature) { lenses = made; invalidate() } }
        }
    }
    private val bevelPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lensPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** キーの大きさごとに、光る縁の画像を1枚だけ作って使い回す。 */
    private fun bevel(w: Int, h: Int, r: Float): android.graphics.Bitmap {
        val key = (w.toLong() shl 32) or h.toLong()
        bevels[key]?.let { return it }
        val d = resources.displayMetrics.density
        val bmp = android.graphics.Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        fun diagonal(edge: Int, middle: Int, far: Int, stop: Float) = android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                android.graphics.Color.argb(edge, 255, 255, 255),
                android.graphics.Color.argb(middle, 255, 255, 255),
                android.graphics.Color.argb(middle, 255, 255, 255),
                android.graphics.Color.argb(far, 255, 255, 255),
            ),
            floatArrayOf(0f, stop, 1f - stop, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        val glow = if (darkTheme) 0xF2 else 0xFF
        // ふんわり光る太い縁（ぼかし）
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f * d
            shader = diagonal(glow, 0, glow * 3 / 4, 0.35f)
            maskFilter = android.graphics.BlurMaskFilter(4f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
        })
        // くっきりした細い縁
        val half = 0.5f * d
        c.drawRoundRect(half, half, w - half, h - half, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
            shader = diagonal(0xE6, 0x1F, 0xB3, 0.4f)
        })
        bevels[key] = bmp
        return bmp
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
            // ふくらみ（最大 3dp）と、指の方への伸び（最大 9dp）
            val swell = 3f * d * strength
            glassRect.inset(-swell, -swell)
            val limit = 9f * d
            val sx = (dx * 0.2f).coerceIn(-limit, limit) * strength
            val sy = (dy * 0.2f).coerceIn(-limit, limit) * strength
            if (sx > 0) glassRect.right += sx else glassRect.left += sx
            if (sy > 0) glassRect.bottom += sy else glassRect.top += sy
        }
        // 角は丸めきる（錠剤の形）。高さか幅の短い方の半分。
        val r = kotlin.math.min(b.width(), b.height()) / 2f
        val character = placed.spec.style == KeyStyle.CHARACTER
        val rest = if (character) (if (darkTheme) 0x1A else 0x8C) else (if (darkTheme) 0x0D else 0x4D)
        val pressed = if (darkTheme) 0x3D else 0xCC
        val alpha = (rest + (pressed - rest) * strength).toInt()
        val lens = lenses[placed]
        if (lens != null) {
            // 光の計算で作ったガラス画像を貼る。押している度合いで「押した版」を重ねる（厚み・屈折・影が強まる）
            drawLens(canvas, lens.first, glassRect, 255)
            if (strength > 0f) drawLens(canvas, lens.second, glassRect, (255 * strength).toInt())
            drawFingerLight(canvas, r, strength, lx, ly)
            return
        }
        // （ガラス画像ができるまでの一瞬だけ）簡易版で描く
        // 明るいテーマだけ、キーの下にごく薄い影（iOS と同じく、キーが少し浮いて見える）
        if (!darkTheme) {
            val drop = 1.5f * d
            canvas.drawRoundRect(glassRect.left, glassRect.top + drop, glassRect.right, glassRect.bottom + drop, r, r, shadowPaint)
        }
        glassFill.color = android.graphics.Color.argb(alpha, 255, 255, 255)
        canvas.drawRoundRect(glassRect, r, r, glassFill)

        drawFingerLight(canvas, r, strength, lx, ly)

        // 光る縁（ふくらんだ時は少し伸ばして貼る）
        val base = placed.bounds
        val restAlpha = if (character) 255 else 0xB3
        bevelPaint.alpha = (restAlpha + (255 - restAlpha) * strength).toInt()
        canvas.drawBitmap(bevel(base.width().toInt(), base.height().toInt(), r), null, glassRect, bevelPaint)
    }

    private val lensRect = RectF()

    /** ガラス画像を [rect]（キーの形）に合わせて貼る。画像には影の余白があるので、その分広げる。 */
    private fun drawLens(canvas: Canvas, lens: Lens.Result, rect: RectF, alpha: Int) {
        val bw = lens.bitmap.width - 2f * lens.margin
        val bh = lens.bitmap.height - 2f * lens.margin
        val sx = rect.width() / bw
        val sy = rect.height() / bh
        lensRect.set(rect.left - lens.margin * sx, rect.top - lens.margin * sy, rect.right + lens.margin * sx, rect.bottom + lens.margin * sy)
        lensPaint.alpha = alpha
        canvas.drawBitmap(lens.bitmap, null, lensRect, lensPaint)
    }

    /** 押している間、指の位置にやわらかい光（押下時の光沢の変化）。 */
    private fun drawFingerLight(canvas: Canvas, r: Float, strength: Float, lx: Float, ly: Float) {
        if (strength > 0f) {
            // 指の位置の光。キーの形の内側だけに描く。
            glassPath.reset()
            glassPath.addRoundRect(glassRect, r, r, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(glassPath)
            val radius = max(glassRect.width(), glassRect.height()) * 0.85f
            val peak = ((if (darkTheme) 0x30 else 0x40) * strength).toInt()
            specularPaint.shader = android.graphics.RadialGradient(
                lx, ly, radius,
                intArrayOf(android.graphics.Color.argb(peak, 255, 255, 255), android.graphics.Color.argb(0, 255, 255, 255)),
                null, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(lx, ly, radius, specularPaint)
            canvas.restore()
        }
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
        val r = kotlin.math.min(previewRect.width(), previewRect.height()) / 2f

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

        val lens = lenses[touch.key]
        if (lens != null) {
            // キーの「押した版」のガラスが、しずくのように持ち上がる
            guideBackgroundPaint.alpha = 255
            drawLens(canvas, lens.second, previewRect, 255)
            guideLabelPaint.color = theme.labelColor
        } else {
            guideBackgroundPaint.alpha = 0xEE
            canvas.drawRoundRect(previewRect, r, r, guideBackgroundPaint)
            guideBackgroundPaint.alpha = 255
            if (glassShader == null) prepareGlass()
            bevelPaint.alpha = 255
            canvas.drawBitmap(bevel(previewRect.width().toInt(), previewRect.height().toInt(), r), null, previewRect, bevelPaint)
            guideLabelPaint.color = theme.flickGuideSelectedLabelColor
        }
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
            if (memento && !glass) {
                if (placed !== gaugeKey && placed.spec.center.action !is KeyAction.OpenPanel) {
                    drawMementoKey(canvas, placed, faceLabel(placed.spec)); return
                }
                drawMementoKey(canvas, placed, null)
            } else if (glass) drawGlassKey(canvas, placed)
            else if (minimal) {
                // 押している間だけ、うっすら面を出す
                if (isPressed(placed)) drawPressed(canvas, placed.bounds, radius, pressedAt(placed))
                else drawPressFade(canvas, placed, radius)
            } else if (paint === keyPressedPaint) {
                // 押したキーは、ふだんの面の上に色がふわっと重なる
                val rest = when {
                    theme.flatKeys -> null
                    placed.spec.style == KeyStyle.CHARACTER -> keyPaint
                    else -> modifierPaint
                }
                if (rest != null) canvas.drawRoundRect(placed.bounds, radius, radius, rest)
                drawPressed(canvas, placed.bounds, radius, pressedAt(placed))
            } else {
                if (paint != null) canvas.drawRoundRect(placed.bounds, radius, radius, paint)
                drawPressFade(canvas, placed, radius)
            }
            if (placed === gaugeKey) {
                drawGauge(canvas, placed, if (glass) kotlin.math.min(placed.bounds.width(), placed.bounds.height()) / 2f else radius)
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
                onPointerUp(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_CANCEL -> {
                popupTouch = null
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
        val key = hitKey(x, y) ?: return
        // A new finger means the previous key is settled; commit it rather than letting a stray
        // move event on the old pointer rewrite it.
        activeTouches.values.forEach { commit(it); pressFades[it.key] = SystemClock.uptimeMillis() }
        cancelPanelLongPress()
        activeTouches.clear()
        cancelRepeat()
        cancelGauge()

        gaugeDone = false
        val touch = Touch(key, x, y)
        activeTouches[pointerId] = touch
        haptics.down()

        popupTouch = null
        if (key.spec.longPress != null || !key.spec.popup.isNullOrEmpty()) {
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
            if (left && gaugeKey == null && !gaugeDone) {
                cancelRepeat()
                startGauge(touch.key)
            } else if (!left && gaugeKey != null) {
                cancelGauge()
            }
            return
        }
        touch.x = x
        touch.y = y
        if (popupTouch === touch) { updatePopupIndex(x); invalidate(); return }
        if (glass) invalidate() // 光と伸びが指を追いかける
        val direction = resolveDirection(touch, x, y)
        if (direction != touch.direction) {
            touch.direction = direction
            // Once the finger leaves the centre the key is no longer a hold-to-repeat candidate.
            cancelRepeat()
            if (direction != FlickDirection.CENTER && longPressTouch === touch) cancelPanelLongPress()
            cancelGauge()
            invalidate()
        }
    }

    private fun onPointerUp(pointerId: Int, x: Float, y: Float) {
        val touch = activeTouches[pointerId] ?: return
        haptics.up()
        pressFades[touch.key] = SystemClock.uptimeMillis()
        // 速いフリックは、指を離す直前の動きが届かないことがある。離した位置でも方向を決め直す
        if (popupTouch === touch) {
            // 並べた文字の中から、指の位置の1つを入れる
            popupTouch = null
            activeTouches.remove(pointerId)
            val pick = touch.key.spec.popup?.getOrNull(popupIndex)
            if (pick != null) listener?.onKeyOutput(KeyOutput(pick, KeyAction.Input(pick)), touch.key.spec, FlickDirection.CENTER)
            invalidate()
            return
        }
        if (!touch.firedOnPress) onPointerMove(pointerId, x, y)
        activeTouches.remove(pointerId)
        if (glass) releasing += Releasing(touch.key, touch.x - touch.downX, touch.y - touch.downY, touch.x, touch.y, SystemClock.uptimeMillis())
        cancelPanelLongPress()
        cancelRepeat()
        cancelGauge()
        commit(touch)
        invalidate()
    }

    /**
     * 指の位置にあるキー。キーとキーの間のすき間（見た目の余白）を押したときは、
     * いちばん近いキーにする（前はすき間を押すと何も起きず、入力が抜けていた）。
     *
     *   ┌──┐ ┌──┐
     *   │あ│・│か│   ・＝すき間 → 近い方のキー
     *   └──┘ └──┘
     */
    private fun hitKey(x: Float, y: Float): PlacedKey? {
        placedKeys.firstOrNull { it.bounds.contains(x, y) }?.let { return it }
        // 同じ段の高さなら、段の端の余白（QWERTYの a の左など）を押しても、その段のいちばん近いキー
        val gap = theme.keyGapDp * resources.displayMetrics.density
        placedKeys.filter { y >= it.bounds.top - gap && y <= it.bounds.bottom + gap }
            .minByOrNull { maxOf(it.bounds.left - x, 0f, x - it.bounds.right) }
            ?.let { k ->
                val dx = maxOf(k.bounds.left - x, 0f, x - k.bounds.right)
                if (dx <= k.bounds.width()) return k
            }
        val reach = 12f * resources.displayMetrics.density
        var best: PlacedKey? = null
        var bestDist = Float.MAX_VALUE
        for (k in placedKeys) {
            val b = k.bounds
            val dx = maxOf(b.left - x, 0f, x - b.right)
            val dy = maxOf(b.top - y, 0f, y - b.bottom)
            val dist = dx * dx + dy * dy
            if (dist < bestDist) { bestDist = dist; best = k }
        }
        return if (bestDist <= reach * reach) best else null
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

        /** レンズ（屈折）画像を作る裏の作業用スレッド。キーボードを作り直しても1本だけ。 */
        private val LENS_WORKER = java.util.concurrent.Executors.newSingleThreadExecutor()

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

        /** 押したときの色が出るまで／消えるまでの時間（ミリ秒）。 */
        private const val PRESS_IN_MS = 70L
        private const val PRESS_OUT_MS = 150L

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

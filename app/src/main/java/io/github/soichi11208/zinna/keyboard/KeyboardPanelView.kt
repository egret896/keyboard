package io.github.soichi11208.zinna.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.widget.LinearLayout
import java.io.File
import kotlin.math.max

/**
 * The keyboard's backdrop: candidate strip and keys sit on top of it.
 *
 * Everything above draws transparently, so this one view owns the panel's appearance. That is what
 * lets a background image run continuously behind the strip and the keys instead of stopping at a
 * seam between them.
 */
@SuppressLint("ViewConstructor")
class KeyboardPanelView(context: Context) : LinearLayout(context) {

    private var bitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    init {
        orientation = VERTICAL
        // The flick guide draws outside the keyboard view's bounds, up over the candidate strip.
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
    }

    /**
     * Sets the background image, or clears it with null.
     *
     * @param opacity 0f–1f. A photo at full strength swallows the key labels, so this is how the
     *   user trades legibility against the image.
     */
    fun setBackgroundImage(file: File?, opacity: Float) {
        bitmap?.recycle()
        bitmap = file?.let { decodeScaled(it) }
        bitmapPaint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        backdropRevision++
        invalidate()
    }

    /** リキッドグラスか。背景には何も足さない（上が明るいグラデーションは古く見えるのでやめた）。 */
    var glass: Boolean = true
        set(value) { field = value; invalidate() }

    /** MEMENTO MORI の背景（深い海の青・差しこむ光・波の線・大きな薄い文字）を描くか。 */
    var memento: Boolean = false
        set(value) { field = value; mementoBackdrop?.recycle(); mementoBackdrop = null; invalidate() }

    /** MEMENTO MORI の種類（0=海の青, 1=満月, 2=大胆, 3=線だけ）。 */
    var mementoStyle: Int = 0
        set(value) { if (field != value) { field = value; mementoBackdrop?.recycle(); mementoBackdrop = null; invalidate() } }

    private var mementoBackdrop: Bitmap? = null

    /** 背景は大きさが変わったときだけ1枚の画像に描いておき、毎回はそれを貼るだけ。 */
    private fun drawMemento(canvas: Canvas) {
        if (!memento || width == 0 || height == 0) return
        val cached = mementoBackdrop
        if (cached == null || cached.width != width || cached.height != height) {
            cached?.recycle()
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val style = mementoStyle
            val colors = when (style) {
                1 -> intArrayOf(0xFF0A1630.toInt(), 0xFF050C1C.toInt(), 0xFF000208.toInt())
                3 -> intArrayOf(0xFF05143A.toInt(), 0xFF041030.toInt(), 0xFF030A1E.toInt())
                else -> intArrayOf(0xFF0B3D91.toInt(), 0xFF062258.toInt(), 0xFF020A1F.toInt())
            }
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, colors, floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            if (style == 1) {
                // 満月：右上に月と、そのまわりのぼんやりした光
                val mx = w * 0.8f; val my = h * 0.16f
                p.shader = android.graphics.RadialGradient(mx, my, h * 0.37f,
                    intArrayOf(0x8CFFF8C8.toInt(), 0x2EFFF0B4, 0x00FFF0B4), floatArrayOf(0f, 0.2f, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = 0xD9FFFAD7.toInt()
                c.drawCircle(mx, my, h * 0.086f, p)
            }
            // 上から差しこむ光の帯（海の青・大胆だけ）
            if (style == 0 || style == 2) for (i in 0 until 6) {
                c.save(); c.translate(w * (0.14f + i * 0.157f), 0f); c.rotate(14f)
                p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h,
                    0x1A7DE6FD, 0x007DE6FD, android.graphics.Shader.TileMode.CLAMP)
                val bw = (16f + (i % 3) * 10f) * d
                c.drawRect(-bw / 2f, -h * 0.1f, bw / 2f, h * 1.1f, p)
                c.restore()
            }
            p.shader = null
            // 水面のゆらめき（波の線）
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.8f * d; p.color = if (style == 1) 0x0DFFF0BE else 0x127DE6FD
            var y = 16f * d
            val path = android.graphics.Path()
            while (y < h) {
                path.reset()
                var x = 0f
                while (x <= w) {
                    val yy = y + kotlin.math.sin(x / (27f * d) + y).toFloat() * 2.3f * d +
                        kotlin.math.sin(x / (9f * d) + y * 0.3f).toFloat() * 0.8f * d
                    if (x == 0f) path.moveTo(x, yy) else path.lineTo(x, yy)
                    x += 3f * d
                }
                c.drawPath(path, p)
                y += 18f * d
            }
            // 大きな薄い文字
            p.style = Paint.Style.FILL; p.color = 0x0CFFFFFF
            p.textSize = h * 0.24f; p.textSkewX = -0.25f; p.isFakeBoldText = true
            p.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            if (style == 2) {
                // 大胆：大きく斜めに、水色で
                p.color = 0x1416CFFB; p.textSize = h * 0.29f
                c.save(); c.translate(w * 0.074f, h * 1.06f); c.rotate(-28f)
            } else {
                c.save(); c.translate(-8f * d, h - 10f * d); c.rotate(-3.5f)
            }
            c.drawText("MEMENTO MORI", 0f, 0f, p)
            c.restore()
            mementoBackdrop = bmp
        }
        canvas.drawBitmap(mementoBackdrop!!, 0f, 0f, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bitmap == null) drawMemento(canvas)
        drawPanel(canvas)
    }

    /** 背景画像が変わった回数。キーのレンズ（屈折）画像を作り直す合図。 */
    var backdropRevision = 0
        private set

    /**
     * [child] の真後ろに見えている背景（色＋画像）を、[child] と同じ大きさの画像にして返す。
     * 背景が単色でも返す（ガラスの色・明るさ・影の色を背景に合わせるため）。
     */
    fun snapshotBehind(child: android.view.View): Bitmap? {
        if (child.width <= 0 || child.height <= 0 || width == 0 || height == 0) return null
        var ox = 0f
        var oy = 0f
        var v: android.view.View = child
        while (v !== this) {
            ox += v.left + v.translationX - v.scrollX
            oy += v.top + v.translationY - v.scrollY
            v = v.parent as? android.view.View ?: return null
        }
        val out = Bitmap.createBitmap(child.width, child.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-ox, -oy)
        background?.draw(c)
        if (bitmap == null) drawMemento(c)
        drawPanel(c)
        return out
    }

    private fun drawPanel(canvas: Canvas) {
        val image = bitmap ?: return
        if (width == 0 || height == 0) return

        // Centre-crop: fill the panel without distorting the picture.
        val scale = max(width.toFloat() / image.width, height.toFloat() / image.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (width - image.width * scale) / 2f,
            (height - image.height * scale) / 2f,
        )
        canvas.drawBitmap(image, matrix, bitmapPaint)
    }

    // Deliberately no recycle on detach: InputMethodService detaches and re-attaches the same input
    // view every time the keyboard hides and shows, so freeing the bitmap there would blank the
    // background for the rest of the session. It is released with the view.

    /**
     * Decodes at roughly panel resolution rather than the camera's.
     *
     * The IME shares a process with nothing that can absorb a 12-megapixel bitmap, and the panel is
     * a few hundred pixels tall — decoding full size would be tens of MB for no visible gain.
     */
    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}

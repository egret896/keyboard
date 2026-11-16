package io.github.soichi11208.zinna.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ガラスの計算が動くこと（と、見た目確認用の画像を build/ に書き出す）。 */
class LensPreviewTest {
    @Test
    fun rendersKeyboardOverPicture() = render(false, "build/lens-preview.argb")

    @Test
    fun rendersFrozenTiles() = render(true, "build/frozen-preview.argb")

    private fun render(frozen: Boolean, path: String) {
        val w = 1080; val h = 640
        val bg = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 150 - 60 * x / w; var g = 110 + 20 * y / h; var b = 240
            // 太陽のような黄色い丸と、斜めの白い線（ゆがみが分かるように）
            val dx = x - 300; val dy = y - 520
            val sun = kotlin.math.exp(-(dx * dx + dy * dy) / (2.0 * 160 * 160)).toFloat()
            r = (r + (255 - r) * sun).toInt(); g = (g + (225 - g) * sun).toInt(); b = (b + (140 - b) * sun).toInt()
            if (((x - y * 260 / h) % 60 + 60) % 60 < 3) { r = 255; g = 255; b = 255 }
            bg[y * w + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val canvas = bg.copyOf()
        val d = 2.6f
        val kw = w / 5f; val kh = (h - 26) / 4f
        for (row in 0 until 4) for (col in 0 until 5) {
            val press = if (row == 1 && col == 2) 1f else 0f
            val seam = 0f
            val left = if (frozen) col * kw + seam else col * kw + 6 * d
            val top = if (frozen) row * (h / 4f) + seam else 13 + row * kh + 6 * d
            val kwi = if (frozen) kw - 2 * seam else kw - 12 * d
            val khi = if (frozen) h / 4f - 2 * seam else kh - 12 * d
            val params = if (frozen) Lens.Params(refract = 0f, frost = 1f, light = 1f, shadow = 0f, square = true) else Lens.Params()
            val p = Lens.renderPixels(bg, w, h, left, top, kwi, khi, d, true, press, params)!!
            val ox = (left - p.margin).toInt(); val oy = (top - p.margin).toInt()
            for (y in 0 until p.height) for (x in 0 until p.width) {
                val tx = ox + x; val ty = oy + y
                if (tx !in 0 until w || ty !in 0 until h) continue
                val s = p.argb[y * p.width + x]; val a = (s ushr 24) / 255f
                val t = canvas[ty * w + tx]
                fun mix(sh: Int) = (((s shr sh) and 0xff) * a + ((t shr sh) and 0xff) * (1 - a)).toInt()
                canvas[ty * w + tx] = (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
            assertTrue(p.argb.any { (it ushr 24) == 255 })
        }
        // 生の画素（幅1080×高さ640、ARGB）を書き出す。PNG への変換はパソコン側で行う
        File("build").mkdirs()
        val bytes = java.nio.ByteBuffer.allocate(canvas.size * 4)
        canvas.forEach { bytes.putInt(it) }
        File(path).writeBytes(bytes.array())
    }
}

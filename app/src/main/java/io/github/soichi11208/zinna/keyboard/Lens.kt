package io.github.soichi11208.zinna.keyboard

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * リキッドグラスの「屈折」：キーの下にある背景画像を、ガラスのレンズ越しのようにゆがめた画像を作る。
 *
 *   キーの断面（横から見たところ）
 *        ╭───────────╮
 *       ╱  まんなか    ╲     ← まんなかはほぼそのまま（ほんの少し拡大）
 *      ╱ 縁ほど強く曲がる ╲   ← 縁の近くでは、外側の景色が内側へ引き込まれて見える
 *
 *  さらに
 *   ・色ごとに曲がり方を少しずらす（散布＝縁がうっすら虹色）
 *   ・軽くぼかす（フロスト）
 *
 * 計算はキーボードの形や背景が変わったときに1回だけ、裏の作業用スレッドで行う。
 * 打つたびには計算しない（できた画像を貼るだけ）ので、入力の重さには影響しない。
 */
internal object Lens {
    private const val REFRACT = 1.0f   // 曲がる強さ
    private const val DEPTH = 0.55f    // 縁から何割の幅まで曲げるか（キーの高さの半分に対して）
    private const val DISPERSION = 0.10f
    private const val MAGNIFY = 0.06f

    /** 背景 [src]（幅 [sw]・高さ [sh] の画素）の、[b] の位置にあるキー1つ分のレンズ画像を作る。 */
    fun render(src: IntArray, sw: Int, sh: Int, b: RectF, density: Float): Bitmap? {
        val w = b.width().toInt()
        val h = b.height().toInt()
        if (w < 4 || h < 4) return null
        val r = min(w, h) / 2f
        val z = DEPTH * r
        val out = IntArray(w * h)
        val cx = b.left + w / 2f
        val cy = b.top + h / 2f
        for (y in 0 until h) {
            val py = y + 0.5f - h / 2f
            for (x in 0 until w) {
                val px = x + 0.5f - w / 2f
                // 角丸の四角（錠剤）までの距離と、縁の外向きの向き
                val qx = abs(px) - (w / 2f - r)
                val qy = abs(py) - (h / 2f - r)
                val ox = max(qx, 0f)
                val oy = max(qy, 0f)
                val outside = hypot(ox, oy)
                val inside = -(outside + min(max(qx, qy), 0f) - r)
                if (inside <= -0.5f) continue
                val nx: Float
                val ny: Float
                if (qx > 0f && qy > 0f) {
                    val l = max(outside, 1e-3f)
                    nx = ox / l * sign(px); ny = oy / l * sign(py)
                } else if (qx > qy) {
                    nx = sign(px); ny = 0f
                } else {
                    nx = 0f; ny = sign(py)
                }
                val t = (1f - inside / z).coerceIn(0f, 1f)
                val disp = REFRACT * z * t * t
                val bx = cx + px * (1f - MAGNIFY)
                val by = cy + py * (1f - MAGNIFY)
                val red = sample(src, sw, sh, bx + nx * disp * (1f + DISPERSION), by + ny * disp * (1f + DISPERSION), 16)
                val green = sample(src, sw, sh, bx + nx * disp, by + ny * disp, 8)
                val blue = sample(src, sw, sh, bx + nx * disp * (1f - DISPERSION), by + ny * disp * (1f - DISPERSION), 0)
                val cover = ((inside + 0.5f).coerceIn(0f, 1f) * 255).toInt()
                out[y * w + x] = (cover shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        frost(out, w, h, max(1, (1.2f * density).toInt()))
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 画素を1色ぶん、なめらかに（となり4つの混ぜ合わせで）取り出す。 */
    private fun sample(src: IntArray, sw: Int, sh: Int, fx: Float, fy: Float, shift: Int): Int {
        val x = fx.coerceIn(0f, sw - 1.001f)
        val y = fy.coerceIn(0f, sh - 1.001f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val ax = x - x0
        val ay = y - y0
        val i = y0 * sw + x0
        val x1 = if (x0 + 1 < sw) 1 else 0
        val y1 = if (y0 + 1 < sh) sw else 0
        val a = (src[i] shr shift) and 0xff
        val bb = (src[i + x1] shr shift) and 0xff
        val c = (src[i + y1] shr shift) and 0xff
        val d = (src[i + y1 + x1] shr shift) and 0xff
        val top = a + (bb - a) * ax
        val bottom = c + (d - c) * ax
        return (top + (bottom - top) * ay).toInt().coerceIn(0, 255)
    }

    /** くもりガラス：色だけを横・縦に軽くぼかす（透明度＝キーの形はそのまま）。 */
    private fun frost(px: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(px.size)
        blurPass(px, tmp, w, h, radius, horizontal = true)
        blurPass(tmp, px, w, h, radius, horizontal = false)
    }

    private fun blurPass(from: IntArray, to: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until length) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (k in -radius..radius) {
                    val j = (i + k).coerceIn(0, length - 1)
                    val p = from[if (horizontal) line * w + j else j * w + line]
                    if ((p ushr 24) == 0) continue
                    r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
                }
                val idx = if (horizontal) line * w + i else i * w + line
                val alpha = from[idx] and 0xff000000.toInt()
                to[idx] = if (n == 0) alpha else alpha or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}

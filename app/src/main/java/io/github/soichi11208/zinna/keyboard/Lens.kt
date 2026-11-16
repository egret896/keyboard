package io.github.soichi11208.zinna.keyboard

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * リキッドグラスのキー1つ分を、光の計算で「厚みのあるガラス」として描いた画像にする。
 *
 *  キーの断面（横から見たところ）
 *        ✦ 光（左上から）
 *         ╲   ╭──────────────╮  ← 縁は丸い面（ベベル）。ここで背景が曲がって見える（屈折・レンズ）
 *          ╲ ╱   ほぼ平ら      ╲   まんなかは平らで、背景がほんの少し拡大されて見える
 *  ░░影░░░░╰──────────────────╯░░ ← 下に、くっきりした接触影＋ふんわり広がる落ち影
 *
 *  1枚の画像に入っているもの（上から順に重なる）
 *   ・鏡面の光（Specular）：左上の縁に細く強く、右下の縁に弱く。縁の丸みに沿って明るさが変わる
 *   ・周りの色の映り込み（Reflection/Fresnel）：縁ほど、周りの色がうっすら映る
 *   ・内側の陰（Inner shadow）：光と反対側（右下）の縁の内側が少し暗い
 *   ・読みやすさの調整（Regular）：背景が明るすぎ/暗すぎるときだけ、文字が読める濃さに寄せる
 *   ・光の散乱（Scattering）＋背景ぼかし（Blur）
 *   ・屈折・レンズ・散布（Refraction/Lensing/Dispersion）：縁ほど強く、色ごとに少しずらす
 *   ・影（Contact + Ambient）：周りの色を混ぜた影を2枚重ねる
 *
 * 計算はキー配置か背景が変わったときだけ、裏のスレッドで1回。打つたびには計算しない。
 */
internal object Lens {
    /** 効果の強さ（デザイントークン）。ここだけ変えれば全体の見た目が変わる。 */
    object Tokens {
        const val BEVEL = 0.30f          // 丸い縁の幅（キーの高さの半分に対する割合）
        const val REFRACT = 1.1f         // 屈折の強さ
        const val DISPERSION = 0.08f     // 色ずれ
        const val MAGNIFY = 0.05f        // まんなかの拡大
        const val BLUR_DP = 2.0f         // 背景ぼかし（Regular）
        const val SCATTER = 0.06f        // 光の散乱
        const val SPEC = 0.85f           // 縁の光の強さ
        const val RIM_DP = 1.3f          // 縁の光の幅（縁にぴったり沿う細い光）
        const val SLOPE = 2.5f           // 縁の丸みの急さ（平らな所へは段差なくなめらかにつながる）
        const val RIM_BACK = 0.45f       // 反対側の縁の光（主光に対する割合）
        const val FRESNEL = 0.12f        // 周りの色の映り込み
        const val INNER_SHADOW = 0.10f   // 内側の陰
        const val INNER_SHADOW_DP = 5f   // 内側の陰の幅
        const val CONTACT = 0.0f         // 接触影（縁の外に黒い線ができて2重に見えるので使わない）
        const val AMBIENT = 0.16f        // 落ち影の濃さ
        const val AMBIENT_BLUR_DP = 10f  // 落ち影の広がり
        const val AMBIENT_DY_DP = 4f     // 落ち影の下へのずれ
        const val MARGIN_DP = 14f        // 影のためにキーの外側へ取る余白
        // 光の向き（左上・手前から）
        const val LX = -0.55f
        const val LY = -0.75f
        const val LZ = 1.0f
    }

    class Result(val bitmap: Bitmap, val margin: Int)

    /** 計算だけの結果（Android の画像にする前）。テストでも使う。 */
    class Pixels(val argb: IntArray, val width: Int, val height: Int, val margin: Int)

    fun render(src: IntArray, sw: Int, sh: Int, b: RectF, density: Float, labelLight: Boolean, press: Float): Result? =
        renderPixels(src, sw, sh, b.left, b.top, b.width(), b.height(), density, labelLight, press)?.let {
            Result(Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888), it.margin)
        }

    /**
     * @param src 背景（キーボードのビューと同じ大きさ）の画素
     * @param b キーの位置
     * @param labelLight 文字が白っぽいテーマか（読みやすさの調整に使う）
     * @param press 0＝ふだん、1＝押している（厚み・屈折・影を強める）
     */
    fun renderPixels(
        src: IntArray, sw: Int, sh: Int, left: Float, top: Float, width: Float, height: Float,
        density: Float, labelLight: Boolean, press: Float,
    ): Pixels? {
        val b = Box(left, top, left + width, top + height)
        val w = width.toInt()
        val h = height.toInt()
        if (w < 4 || h < 4) return null
        val m = (Tokens.MARGIN_DP * density).toInt()
        val bw = w + 2 * m
        val bh = h + 2 * m
        val n = bw * bh
        val r = min(w, h) / 2f
        val z = Tokens.BEVEL * r * (1f + 0.25f * press)
        val cx = b.left + w / 2f
        val cy = b.top + h / 2f

        // 背景の平均色と明るさ（影の色・映り込み・読みやすさの調整に使う）
        var ar = 0L; var ag = 0L; var ab = 0L; var count = 0
        val x0 = b.left.toInt().coerceIn(0, sw - 1); val x1 = b.right.toInt().coerceIn(x0 + 1, sw)
        val y0 = b.top.toInt().coerceIn(0, sh - 1); val y1 = b.bottom.toInt().coerceIn(y0 + 1, sh)
        var yy = y0
        while (yy < y1) {
            var xx = x0
            while (xx < x1) {
                val p = src[yy * sw + xx]
                ar += (p shr 16) and 0xff; ag += (p shr 8) and 0xff; ab += p and 0xff; count++
                xx += 3
            }
            yy += 3
        }
        val avgR = ar.toFloat() / count; val avgG = ag.toFloat() / count; val avgB = ab.toFloat() / count
        val lum = (0.299f * avgR + 0.587f * avgG + 0.114f * avgB) / 255f

        // 1) 屈折した背景（色ごとに少しずらす）
        val red = FloatArray(n); val green = FloatArray(n); val blue = FloatArray(n)
        val cover = FloatArray(n)
        val slopeX = FloatArray(n); val slopeY = FloatArray(n); val flat = FloatArray(n); val edge = FloatArray(n)
        val normX = FloatArray(n); val normY = FloatArray(n); val depth = FloatArray(n)
        for (y in 0 until bh) {
            val py = y + 0.5f - m - h / 2f
            for (x in 0 until bw) {
                val px = x + 0.5f - m - w / 2f
                val i = y * bw + x
                val qx = abs(px) - (w / 2f - r)
                val qy = abs(py) - (h / 2f - r)
                val ox = max(qx, 0f); val oy = max(qy, 0f)
                val outside = hypot(ox, oy)
                val inside = -(outside + min(max(qx, qy), 0f) - r)
                cover[i] = (inside + 0.5f).coerceIn(0f, 1f)
                if (cover[i] <= 0f) continue
                val nx: Float; val ny: Float
                if (qx > 0f && qy > 0f) {
                    val l = max(outside, 1e-3f); nx = ox / l * sign(px); ny = oy / l * sign(py)
                } else if (qx > qy) { nx = sign(px); ny = 0f } else { nx = 0f; ny = sign(py) }
                val s = 1f - (inside / z).coerceIn(0f, 1f)        // 縁で1、平らな所で0
                // 傾きは平らな所で0、縁へ向かってなめらかに増える（途中に折れ目＝2本目の線ができない）
                val slope = Tokens.SLOPE * s * s
                val nz = 1f / sqrt(1f + slope * slope)
                slopeX[i] = nx * slope * nz; slopeY[i] = ny * slope * nz; flat[i] = nz; edge[i] = s
                normX[i] = nx; normY[i] = ny; depth[i] = max(inside, 0f)
                val disp = Tokens.REFRACT * (1f + 0.4f * press) * z * s * s
                val bx = cx + px * (1f - Tokens.MAGNIFY)
                val by = cy + py * (1f - Tokens.MAGNIFY)
                val kr = 1f + Tokens.DISPERSION; val kb = 1f - Tokens.DISPERSION
                red[i] = sample(src, sw, sh, bx + nx * disp * kr, by + ny * disp * kr, 16)
                green[i] = sample(src, sw, sh, bx + nx * disp, by + ny * disp, 8)
                blue[i] = sample(src, sw, sh, bx + nx * disp * kb, by + ny * disp * kb, 0)
            }
        }
        // 2) 背景ぼかし
        val blurR = max(1, (Tokens.BLUR_DP * density).toInt() / 2)
        for (ch in arrayOf(red, green, blue)) blur3(ch, cover, bw, bh, blurR)

        // 3) 散乱・読みやすさ・光
        val scatterR = avgR * 0.6f + 255f * 0.4f; val scatterG = avgG * 0.6f + 255f * 0.4f; val scatterB = avgB * 0.6f + 255f * 0.4f
        // 読みやすさ：明るすぎる背景は、同じ色味のまま暗く（黒を混ぜると濁るので）
        val tint: Float; val darken: Boolean
        if (labelLight) {
            if (lum > 0.55f) { tint = 0.06f + (lum - 0.55f) * 0.7f; darken = true } else { tint = 0.06f; darken = false }
        } else {
            tint = 0.10f + max(0f, 0.70f - lum) * 0.9f; darken = false
        }
        val tintR = if (darken) avgR * 0.35f else 255f
        val tintG = if (darken) avgG * 0.35f else 255f
        val tintB = if (darken) avgB * 0.35f else 255f
        val ll = sqrt(Tokens.LX * Tokens.LX + Tokens.LY * Tokens.LY + Tokens.LZ * Tokens.LZ)
        val lx = Tokens.LX / ll; val ly = Tokens.LY / ll; val lz = Tokens.LZ / ll
        val l2 = sqrt(lx * lx + ly * ly)
        val l2x = lx / l2; val l2y = ly / l2
        val envR = min(255f, avgR * 1.25f + 40f); val envG = min(255f, avgG * 1.25f + 40f); val envB = min(255f, avgB * 1.25f + 40f)
        val specGain = Tokens.SPEC * (1f + 0.3f * press)

        val out = IntArray(n)
        // 影（外側）：キーの形をぼかして、下へずらす
        val contact = cover.copyOf(); blur3(contact, null, bw, bh, max(1, (1.2f * density).toInt()))
        val ambient = cover.copyOf(); blur3(ambient, null, bw, bh, max(1, (Tokens.AMBIENT_BLUR_DP * (1f + 0.5f * press) * density / 1.7f).toInt()))
        val cdy = (0.8f * density).toInt()
        val ady = (Tokens.AMBIENT_DY_DP * (1f + press) * density).toInt()
        val shadowR = (avgR * 0.35f).toInt(); val shadowG = (avgG * 0.35f).toInt(); val shadowB = (avgB * 0.35f).toInt()

        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            val c = cover[i]
            // 影
            val ci = if (y - cdy in 0 until bh) contact[(y - cdy) * bw + x] else 0f
            val ai = if (y - ady in 0 until bh) ambient[(y - ady) * bw + x] else 0f
            val shadowA = ((Tokens.CONTACT * ci + Tokens.AMBIENT * (1f + 0.5f * press) * ai).coerceIn(0f, 1f) * (1f - c))
            if (c <= 0f) {
                out[i] = ((shadowA * 255).toInt() shl 24) or (shadowR shl 16) or (shadowG shl 8) or shadowB
                continue
            }
            var cr = red[i] * (1f - Tokens.SCATTER) + scatterR * Tokens.SCATTER
            var cg = green[i] * (1f - Tokens.SCATTER) + scatterG * Tokens.SCATTER
            var cb = blue[i] * (1f - Tokens.SCATTER) + scatterB * Tokens.SCATTER
            cr += (tintR - cr) * tint; cg += (tintG - cg) * tint; cb += (tintB - cb) * tint
            val nz = flat[i]
            val facing = -(normX[i] * l2x + normY[i] * l2y)        // 光の方を向いた縁で 1、反対で -1
            // 内側の陰（光と反対を向いた縁の内側）
            val shade = max(0f, -facing) * Tokens.INNER_SHADOW * kotlin.math.exp(-depth[i] / (Tokens.INNER_SHADOW_DP * density))
            cr *= 1f - shade; cg *= 1f - shade; cb *= 1f - shade
            // 周りの色の映り込み
            val fres = Tokens.FRESNEL * (1f - nz).toDouble().pow(2.5).toFloat()
            cr += (envR - cr) * fres; cg += (envG - cg) * fres; cb += (envB - cb) * fres
            // 鏡面の光（左上に強く、右下に弱く）
            // 縁の光：縁にぴったり沿う細い光。光の方を向いた所ほど明るく、向きでなめらかに変わる
            val rim = kotlin.math.exp(-depth[i] / (Tokens.RIM_DP * density))
            val toward = max(0f, facing); val away = max(0f, -facing)
            val spec = specGain * rim * (toward * sqrt(toward) + Tokens.RIM_BACK * away * sqrt(away))
            cr += 255f * spec; cg += 255f * spec; cb += 255f * spec
            // 縁のなめらかさ：形の外の半ピクセルは影と混ぜる
            val a = c + shadowA * (1f - c)
            val k = if (a > 0f) c / a else 1f
            val fr = cr.coerceIn(0f, 255f) * k + shadowR * (1f - k)
            val fg = cg.coerceIn(0f, 255f) * k + shadowG * (1f - k)
            val fb = cb.coerceIn(0f, 255f) * k + shadowB * (1f - k)
            out[i] = ((a * 255).toInt().coerceIn(0, 255) shl 24) or (fr.toInt() shl 16) or (fg.toInt() shl 8) or fb.toInt()
        }
        return Pixels(out, bw, bh, m)
    }

    private class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun sample(src: IntArray, sw: Int, sh: Int, fx: Float, fy: Float, shift: Int): Float {
        val x = fx.coerceIn(0f, sw - 1.001f)
        val y = fy.coerceIn(0f, sh - 1.001f)
        val x0 = x.toInt(); val y0 = y.toInt()
        val ax = x - x0; val ay = y - y0
        val i = y0 * sw + x0
        val x1 = if (x0 + 1 < sw) 1 else 0
        val y1 = if (y0 + 1 < sh) sw else 0
        val a = ((src[i] shr shift) and 0xff).toFloat()
        val bb = ((src[i + x1] shr shift) and 0xff).toFloat()
        val c = ((src[i + y1] shr shift) and 0xff).toFloat()
        val d = ((src[i + y1 + x1] shr shift) and 0xff).toFloat()
        val top = a + (bb - a) * ax
        val bottom = c + (d - c) * ax
        return top + (bottom - top) * ay
    }

    /**
     * ぼかし（箱型を3回＝ほぼガウスぼかし）。[mask] があれば、その外側の画素は混ぜない
     * （キーの外の色が縁ににじまないように）。
     */
    private fun blur3(v: FloatArray, mask: FloatArray?, w: Int, h: Int, radius: Int) {
        val tmp = FloatArray(v.size)
        repeat(3) {
            box(v, tmp, mask, w, h, radius, true)
            box(tmp, v, mask, w, h, radius, false)
        }
    }

    private fun box(from: FloatArray, to: FloatArray, mask: FloatArray?, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        for (line in 0 until lines) {
            var sum = 0f; var weight = 0f
            fun idx(j: Int) = if (horizontal) line * w + j else j * w + line
            fun wt(j: Int) = if (mask == null) 1f else (if (mask[idx(j)] > 0f) 1f else 0f)
            for (j in 0 until min(radius, length)) { val q = wt(j); sum += from[idx(j)] * q; weight += q }
            for (i in 0 until length) {
                val add = i + radius
                if (add < length) { val q = wt(add); sum += from[idx(add)] * q; weight += q }
                val drop = i - radius - 1
                if (drop >= 0) { val q = wt(drop); sum -= from[idx(drop)] * q; weight -= q }
                to[idx(i)] = if (weight > 1e-4f) sum / weight else from[idx(i)]
            }
        }
    }
}

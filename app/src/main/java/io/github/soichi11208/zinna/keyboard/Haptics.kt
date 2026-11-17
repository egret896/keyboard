package io.github.soichi11208.zinna.keyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * キーを押したときの振動。
 *
 *  0 オフ      ：振動しない
 *  1 軽い      ：コツ（ごく短く細かい）
 *  2 カチッ    ：はっきりしたクリック
 *  3 押し込み  ：押したとき「ふにっ」と沈み、離したとき「カチッ」と戻る（本物のボタンのように）
 *
 * スマホの振動モーターは1つなので、押した場所だけを震わせることはできない。
 * そのかわり、最近の機種の細かい振動（プリミティブ）を使って、短く・はっきりした手ごたえにする。
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    var mode = 2
    /** 強さ 0.1〜1。 */
    var strength = 0.6f

    private val primitives: Boolean by lazy {
        Build.VERSION.SDK_INT >= 30 && vibrator?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        ) == true
    }

    /** 指が触れたとき。 */
    fun down() {
        when (mode) {
            1 -> play(PRIM_TICK, 6L)
            2 -> play(PRIM_CLICK, 12L)
            3 -> play(PRIM_LOW_TICK, 5L, 0.7f)
        }
    }

    /** 指を離したとき（押し込みだけ）。 */
    fun up() {
        if (mode == 3) play(PRIM_CLICK, 10L)
    }

    /**
     * 全消しの光に合わせた振動：コツ・コツ・コツ…と [count] 回、[gapMs] ごとに。
     * 最初は [startMs] 後（光がいちばん右のキーに届くころ）。少しずつ弱くして、流れていく感じにする。
     */
    fun wave(count: Int, gapMs: Long, startMs: Long) {
        if (mode == 0) return
        val v = vibrator ?: return
        try {
            if (primitives && Build.VERSION.SDK_INT >= 30) {
                val c = VibrationEffect.startComposition()
                for (i in 0 until count) {
                    val s = (strength * (1f - i * 0.5f / count)).coerceIn(0.05f, 1f)
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, s, if (i == 0) startMs.toInt() else gapMs.toInt())
                }
                v.vibrate(c.compose())
            } else if (Build.VERSION.SDK_INT >= 26) {
                val timings = LongArray(count * 2)
                val amps = IntArray(count * 2)
                for (i in 0 until count) {
                    timings[i * 2] = if (i == 0) startMs else (gapMs - 6L).coerceAtLeast(1L)
                    timings[i * 2 + 1] = 6L
                    amps[i * 2 + 1] = if (v.hasAmplitudeControl()) (strength * 255 * (1f - i * 0.5f / count)).toInt().coerceIn(1, 255) else 255
                }
                v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            }
        } catch (_: SecurityException) {
        }
    }

    private fun play(primitive: Int, fallbackMs: Long, scale: Float = 1f) {
        val v = vibrator ?: return
        val s = (strength * scale).coerceIn(0.05f, 1f)
        try {
            if (primitives && Build.VERSION.SDK_INT >= 30) {
                val p = when (primitive) {
                    PRIM_TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
                    PRIM_LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                    else -> VibrationEffect.Composition.PRIMITIVE_CLICK
                }
                v.vibrate(VibrationEffect.startComposition().addPrimitive(p, s).compose())
            } else if (Build.VERSION.SDK_INT >= 26) {
                val amp = if (v.hasAmplitudeControl()) (s * 255).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                v.vibrate(VibrationEffect.createOneShot(fallbackMs, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(fallbackMs)
            }
        } catch (_: SecurityException) {
        }
    }

    private companion object {
        const val PRIM_CLICK = 0
        const val PRIM_TICK = 1
        const val PRIM_LOW_TICK = 2
    }
}

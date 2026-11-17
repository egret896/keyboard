package io.github.soichi11208.zinna.theme

import android.graphics.Typeface
import android.os.Build

/** フォント名と太さから Typeface を作る（カスタマイズの設定用）。 */
object Fonts {
    /** 設定で選べるフォント（表示名 → Android のフォント名）。 */
    val CHOICES = listOf(
        "標準" to "",
        "ゴシック" to "sans-serif",
        "明朝" to "serif",
        "等幅" to "monospace",
        "細め" to "sans-serif-light",
        "細長" to "sans-serif-condensed",
    )

    /** 設定で選べる太さ（表示名 → 100〜900。0 ＝標準）。 */
    val WEIGHTS = listOf(
        "標準" to 0,
        "細い" to 300,
        "ふつう" to 400,
        "やや太い" to 500,
        "太い" to 700,
        "極太" to 900,
    )

    fun typeface(family: String, weight: Int): Typeface {
        val base = if (family.isEmpty()) Typeface.DEFAULT else Typeface.create(family, Typeface.NORMAL)
        if (weight <= 0) return base
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
}

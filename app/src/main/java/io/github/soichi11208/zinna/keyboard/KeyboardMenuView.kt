package io.github.soichi11208.zinna.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.TextView
import io.github.soichi11208.zinna.theme.KeyboardTheme

/**
 * 「☺記」を長押ししたときに、キーボードの場所に出るメニュー。
 *
 *  ┌──────────┬──────────┬──────────┐
 *  │ アプリ設定 │ 単語登録  │ 片手モード │
 *  ├──────────┼──────────┼──────────┤
 *  │ 高さ −    │ 高さ ＋   │ 左右分割  │
 *  ├──────────┴──────────┴──────────┤
 *  │              閉じる              │
 *  └────────────────────────────────┘
 *
 * 文字だけの落ち着いたボタン（絵文字は使わない）。
 */
@SuppressLint("ViewConstructor")
class KeyboardMenuView(
    context: Context,
    private val theme: KeyboardTheme,
) : LinearLayout(context) {

    class Item(val label: () -> String, val onClick: () -> Unit)

    private val density = resources.displayMetrics.density
    private val buttons = mutableListOf<Pair<TextView, Item>>()

    init {
        orientation = VERTICAL
        val pad = (8 * density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    /** ボタンを並べ直す。[rows] の1行がメニューの1段になる。 */
    fun setItems(rows: List<List<Item>>) {
        removeAllViews()
        buttons.clear()
        for (row in rows) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (item in row) {
                val button = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextColor(theme.labelColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    // キーと同じ：縁なしの半透明（暗いテーマは薄く、明るいテーマは白く）
                    background = GradientDrawable().apply {
                        cornerRadius = 1000f * density // 丸めきる（錠剤の形）
                        val c = theme.labelColor
                        val dark = (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) > 384
                        setColor(android.graphics.Color.argb(if (dark) 0x2E else 0xF2, 255, 255, 255))
                    }
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        Anim.tap(it)
                        item.onClick()
                        refreshLabels()
                    }
                }
                val m = (4 * density).toInt()
                line.addView(button, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(m, m, m, m)
                })
                buttons += button to item
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        refreshLabels()
    }

    /** 「片手モード：右」のように状態で変わる文字を書き直す。 */
    fun refreshLabels() {
        for ((view, item) in buttons) view.text = item.label()
    }
}

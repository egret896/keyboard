package io.github.soichi11208.zinna.keyboard

/**
 * Fold を開いたときの左右分割レイアウトを作る。
 *
 *  閉じたとき（いつもの12キー）       開いたとき（同じキーボードを左右に2つ）
 *  ┌─┬─┬─┬─┬─┐                 ┌─┬─┬─┬─┬─┐   ┌─┬─┬─┬─┬─┐
 *  │↶│あ│か│さ│⌫│                 │↶│あ│か│さ│⌫│   │↶│あ│か│さ│⌫│
 *  │◀│た│な│は│▶│      →          │◀│た│な│は│▶│   │◀│た│な│は│▶│
 *  │☺│ま│や│ら│␣│                 │☺│ま│や│ら│␣│   │☺│ま│や│ら│␣│
 *  │A1│小│わ│、│↵│                 │A1│小│わ│、│↵│   │A1│小│わ│、│↵│
 *  └─┴─┴─┴─┴─┘                 └─┴─┴─┴─┴─┘   └─┴─┴─┴─┴─┘
 *
 * いつもの配置をそのまま左右に並べ、真ん中を少し空ける。
 * 5列の行（フリック配列）だけが対象で、QWERTY など他の形はそのまま返す。
 */
object SplitLayout {

    /** 真ん中の隙間（キー1つの幅を 1 としたとき）。 */
    private const val CENTER_GAP = 0.6f

    fun split(layout: KeyboardLayout): KeyboardLayout {
        if (layout.rows.isEmpty() || layout.rows.any { it.keys.size != 5 }) return layout
        val rows = layout.rows.map { row ->
            val left = row.keys.dropLast(1) + row.keys.last().copy(gapAfter = CENTER_GAP)
            row.copy(keys = left + row.keys, padStart = 0f, padEnd = 0f)
        }
        return layout.copy(rows = rows)
    }
}

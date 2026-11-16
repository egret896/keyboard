package io.github.soichi11208.zinna.keyboard

/**
 * Fold を開いたときの左右分割レイアウトを作る。
 *
 *  閉じたとき（いつもの12キー）       開いたとき（両手フリック）
 *  ┌─┬─┬─┬─┬─┐                 ┌─┬─┬─┬──┬──┬─┬─┬─┐
 *  │↶│あ│か│さ│⌫│                 │あ│か│さ│ ↶│ ⌫│あ│か│さ│
 *  │◀│た│な│は│▶│      →          │た│な│は│ ◀│ ▶│た│な│は│
 *  │☺│ま│や│ら│␣│                 │ま│や│ら│ ☺│ ␣│ま│や│ら│
 *  │A1│小│わ│、│↵│                 │小│わ│、│A1│ ↵│小│わ│、│
 *  └─┴─┴─┴─┴─┘                 └─┴─┴─┴──┴──┴─┴─┴─┘
 *
 * 左右どちらの親指でも同じ文字が打てるように、文字キーを左右に同じものを置き、
 * 機能キーは真ん中にまとめる。5列の行（フリック配列）だけが対象で、
 * QWERTY など他の形はそのまま返す。
 */
object SplitLayout {

    /** 真ん中の機能キーの幅（文字キーを 1 としたとき）。 */
    private const val FUNCTION_KEY_WEIGHT = 0.8f

    fun split(layout: KeyboardLayout): KeyboardLayout {
        if (layout.rows.isEmpty() || layout.rows.any { it.keys.size != 5 }) return layout
        val rows = layout.rows.map { row ->
            val (left, c1, c2, c3, right) = row.keys
            val chars = listOf(c1, c2, c3)
            row.copy(
                keys = chars +
                    left.copy(weight = FUNCTION_KEY_WEIGHT) +
                    right.copy(weight = FUNCTION_KEY_WEIGHT) +
                    chars,
                padStart = 0f,
                padEnd = 0f,
            )
        }
        return layout.copy(rows = rows)
    }
}

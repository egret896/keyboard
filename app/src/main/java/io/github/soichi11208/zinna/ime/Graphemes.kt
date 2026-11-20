package io.github.soichi11208.zinna.ime

/**
 * 「見た目の1文字」（書記素クラスタ）の区切りを探す。
 *
 * 絵文字は中身が何個もの部品でできていることがある。⌫ や ◀▶ が部品1個ずつ動くと、
 * 半分だけ消えて「�」になったり、カーソルが動かないように見えたりする。
 *
 *    👍🏽  = 👍 + 肌の色             （4 char）
 *    👨‍👩‍👧 = 👨 + ZWJ + 👩 + ZWJ + 👧   （8 char）
 *    ❤️  = ❤ + 異体字セレクタ        （2 char）
 *    🇯🇵  = 🇯 + 🇵                   （4 char）
 *    1️⃣  = 1 + 異体字セレクタ + ⃣   （3 char）
 *
 * Android の BreakIterator は OS のバージョンで新しい絵文字の扱いが違うので、
 * よく使うルールだけを自前で持つ（どの端末でも同じ動きになるように）。
 */
internal object Graphemes {

    /** [text] の最後の「見た目の1文字」が何 char か。空なら 0。 */
    fun lastLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        var last = 0
        var i = 0
        while (i < text.length) {
            last = i
            i = nextBoundary(text, i)
        }
        return text.length - last
    }

    /** [text] の最後から [count] 文字ぶんが何 char か（足りなければ全部）。 */
    fun lastLength(text: CharSequence, count: Int): Int {
        val bounds = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            bounds.add(i)
            i = nextBoundary(text, i)
        }
        if (count <= 0 || bounds.isEmpty()) return 0
        val idx = (bounds.size - count).coerceAtLeast(0)
        return text.length - bounds[idx]
    }

    /** [text] の最初から [count] 文字ぶんが何 char か（足りなければ全部）。 */
    fun firstLength(text: CharSequence, count: Int): Int {
        var i = 0
        var n = 0
        while (i < text.length && n < count) {
            i = nextBoundary(text, i)
            n++
        }
        return i
    }

    /** [start] から始まる1文字の、次の区切りの位置。 */
    internal fun nextBoundary(text: CharSequence, start: Int): Int {
        val base = Character.codePointAt(text, start)
        var i = start + Character.charCount(base)
        // 改行の CR LF は1つ
        if (base == CR && i < text.length && text[i].code == LF) return i + 1
        if (base == CR || base == LF) return i

        var regionalCount = if (isRegionalIndicator(base)) 1 else 0
        var afterZwj = false
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val join = when {
                afterZwj -> true // ZWJ の次の絵文字はくっつく（👨‍👩‍👧）
                isExtend(cp) -> true
                cp == ZWJ -> true
                // 国旗は地域文字2つで1つ（🇯🇵）
                regionalCount == 1 && isRegionalIndicator(cp) -> { regionalCount = 2; true }
                else -> false
            }
            if (!join) break
            afterZwj = cp == ZWJ
            i += Character.charCount(cp)
        }
        return i
    }

    private fun isExtend(cp: Int): Boolean {
        if (cp in 0xFE00..0xFE0F) return true        // 異体字セレクタ（❤️）
        if (cp in 0xE0100..0xE01EF) return true      // 異体字セレクタ（補助）
        if (cp in 0x1F3FB..0x1F3FF) return true      // 肌の色
        if (cp in 0xE0020..0xE007F) return true      // タグ（🏴 の地域旗）
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),       // 濁点の結合文字 ゛ など
            Character.ENCLOSING_MARK.toInt(),         // キーキャップ ⃣
            Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> false
        }
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private const val ZWJ = 0x200D
    private const val CR = 0x0D
    private const val LF = 0x0A
}

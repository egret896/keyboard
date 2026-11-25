package io.github.soichi11208.zinna.ime

/**
 * 「出さない候補」を、その文だけでなく「変換のしかた」で覚える。
 *
 *   除外：読み「それはなんなん」→「それは南なん」
 *     前後の同じ部分（それは … なん）をけずる → 「なん」→「南」
 *     右どなりの1文字を足して、場面をしぼる → 「なんな」→「南な」
 *   以後、読みに「なんな」があり、候補に「南な」がある候補は出さない
 *     それはなんなんやろ → それは南なんやろ  出ない
 *     なんなん          → 南なん            出ない
 *     なんきょく        → 南極              出る
 */
object HiddenRules {

    data class Rule(val reading: String, val word: String) {
        fun encode(): String = "$reading\t$word"
        fun matches(fullReading: String, candidate: String): Boolean =
            reading.isNotEmpty() && word.isNotEmpty() && fullReading.contains(reading) && candidate.contains(word)
    }

    fun decode(s: String): Rule? {
        val tab = s.indexOf('\t')
        if (tab <= 0 || tab == s.length - 1) return null
        return Rule(s.substring(0, tab), s.substring(tab + 1))
    }

    /** 除外した候補から、ちがう部分（＋となり1文字）を取り出す。作れなければ null。 */
    fun derive(reading: String, candidate: String): Rule? {
        if (reading.isEmpty() || candidate.isEmpty() || reading == candidate) return null
        // 前から同じ文字をけずる
        var p = 0
        while (p < reading.length && p < candidate.length && reading[p] == candidate[p]) p++
        // 後ろから同じ文字をけずる（前でけずった所とは重ならないように）
        var s = 0
        while (s < reading.length - p && s < candidate.length - p &&
            reading[reading.length - 1 - s] == candidate[candidate.length - 1 - s]) s++
        val readEnd = reading.length - s
        val candEnd = candidate.length - s
        if (p >= readEnd || p >= candEnd) return null
        // 右どなりがあれば1文字足す。なければ左どなりを1文字足す
        return when {
            s > 0 -> Rule(reading.substring(p, readEnd + 1), candidate.substring(p, candEnd + 1))
            p > 0 -> Rule(reading.substring(p - 1, readEnd), candidate.substring(p - 1, candEnd))
            else -> Rule(reading, candidate)
        }
    }
}

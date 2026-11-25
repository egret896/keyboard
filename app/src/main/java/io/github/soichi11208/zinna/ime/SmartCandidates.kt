package io.github.soichi11208.zinna.ime

import java.util.Calendar

/**
 * 変換候補に足す「便利な候補」。ネットは使わず、スマホの中だけで作る。
 *
 *  きょう → 2026/11/17・11月17日・2026年11月17日(火) …
 *  いま   → 10:25・10時25分
 *  12*3=  → 36・12*3=36
 *  12000  → 一万二千・¥12,000・12,000円 …
 *  11/27  → 11/27(金)・11月27日(金) …     らいしゅうのきんようび → 12/4(金) …
 *  5きろ   → 5km・5kg・5キロ
 */
object SmartCandidates {

    private val WEEK = arrayOf("日", "月", "火", "水", "木", "金", "土")

    /** よみ（ひらがな）が日付・時刻の言葉なら、その候補。 */
    fun dateTime(reading: String, now: Calendar = Calendar.getInstance()): List<String> {
        fun day(offset: Int): List<String> {
            val c = now.clone() as Calendar
            c.add(Calendar.DAY_OF_MONTH, offset)
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH) + 1
            val d = c.get(Calendar.DAY_OF_MONTH)
            val w = WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]
            return listOf(
                "%d/%02d/%02d".format(y, m, d),
                "${m}月${d}日",
                "${y}年${m}月${d}日(${w})",
                "$m/$d($w)",
                "%d-%02d-%02d".format(y, m, d),
            )
        }
        val h = now.get(Calendar.HOUR_OF_DAY)
        val min = now.get(Calendar.MINUTE)
        val time = listOf("%d:%02d".format(h, min), "${h}時${min}分")
        return when (reading) {
            "きょう", "ほんじつ" -> day(0)
            "あした", "あす", "みょうにち" -> day(1)
            "あさって" -> day(2)
            "しあさって" -> day(3)
            "きのう", "さくじつ" -> day(-1)
            "おととい" -> day(-2)
            "さきおととい" -> day(-3)
            "いま", "じこく", "げんざい" -> time
            "にちじ" -> listOf(day(0)[0] + " " + time[0], day(0)[1] + " " + time[1])
            "ようび" -> listOf(WEEK[now.get(Calendar.DAY_OF_WEEK) - 1] + "曜日")
            else -> weekdayDate(reading, now) ?: monthYear(reading, now)
        }
    }

    private val WEEK_READING = listOf("にち", "げつ", "か", "すい", "もく", "きん", "ど")

    /** げつようび・らいしゅうのきんようび → その日の日付（曜日つき）。 */
    private fun weekdayDate(reading: String, now: Calendar): List<String>? {
        val m = Regex("^(こんしゅう|らいしゅう|さらいしゅう|せんしゅう)?(?:の)?(にち|げつ|か|すい|もく|きん|ど)ようび$")
            .find(reading) ?: return null
        val target = WEEK_READING.indexOf(m.groupValues[2])  // 0 = 日曜
        val today = now.get(Calendar.DAY_OF_WEEK) - 1
        // 週は月曜はじまり（日曜はその週の最後）
        fun mon(d: Int) = (d + 6) % 7
        val offset = when (m.groupValues[1]) {
            "" -> (target - today + 7) % 7                          // 次に来るその曜日（今日をふくむ）
            "こんしゅう" -> mon(target) - mon(today)
            "らいしゅう" -> mon(target) - mon(today) + 7
            "さらいしゅう" -> mon(target) - mon(today) + 14
            else -> mon(target) - mon(today) - 7
        }
        val c = now.clone() as Calendar
        c.add(Calendar.DAY_OF_MONTH, offset)
        return withWeekday(c, includeYear = false)
    }

    /** こんげつ・らいねん など → 2026年12月・令和8年 など。 */
    private fun monthYear(reading: String, now: Calendar): List<String> {
        val y = now.get(Calendar.YEAR)
        fun month(add: Int): List<String> {
            val c = now.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.add(Calendar.MONTH, add)
            val cy = c.get(Calendar.YEAR); val cm = c.get(Calendar.MONTH) + 1
            return listOf("${cm}月", "${cy}年${cm}月", "%d/%02d".format(cy, cm))
        }
        fun year(add: Int) = listOf("${y + add}年", "令和${y + add - 2018}年")
        return when (reading) {
            "こんげつ" -> month(0)
            "らいげつ" -> month(1)
            "せんげつ" -> month(-1)
            "ことし", "こんねん", "ほんねん" -> year(0)
            "らいねん" -> year(1)
            "さらいねん" -> year(2)
            "きょねん", "さくねん" -> year(-1)
            "おととし" -> year(-2)
            else -> emptyList()
        }
    }

    private fun withWeekday(c: Calendar, includeYear: Boolean): List<String> {
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        val w = WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]
        val list = mutableListOf("$m/$d($w)", "${m}月${d}日(${w})", "${m}月${d}日（${w}曜日）")
        val withYear = listOf("%d/%02d/%02d(%s)".format(y, m, d, w), "${y}年${m}月${d}日(${w})")
        return if (includeYear) withYear + list else list + withYear
    }

    /** 打った日付（11/27・2026/11/27・11がつ27にち）に曜日をつける。 */
    fun dateWithWeekday(preedit: String, now: Calendar = Calendar.getInstance()): List<String> {
        val t = digits(preedit).replace('・', '/')
        val full = Regex("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$").find(t)
        val short = Regex("^(\\d{1,2})(?:/|がつ)(\\d{1,2})(?:にち)?$").find(t)
        val (y, m, d) = when {
            full != null -> full.groupValues.drop(1).map(String::toInt)
            short != null -> listOf(now.get(Calendar.YEAR)) + short.groupValues.drop(1).map(String::toInt)
            else -> return emptyList()
        }
        if (m !in 1..12 || d !in 1..31) return emptyList()
        val c = Calendar.getInstance().apply { clear(); isLenient = false; set(y, m - 1, d) }
        return try { c.timeInMillis; withWeekday(c, includeYear = full != null) } catch (_: Exception) { emptyList() }
    }

    // ---- 数字 ----

    private val KANJI_DIGIT = "〇一二三四五六七八九"
    private val BIG_UNIT = listOf("", "万", "億", "兆", "京")

    /** 12000 → 一万二千（0 → 〇）。 */
    fun toKanji(n: Long): String {
        if (n == 0L) return "〇"
        val sb = StringBuilder()
        var rest = n
        var group = 0
        val parts = mutableListOf<String>()
        while (rest > 0) {
            val g = (rest % 10000).toInt()
            if (g > 0) parts.add(0, smallKanji(g) + BIG_UNIT[group])
            rest /= 10000
            group++
        }
        parts.forEach { sb.append(it) }
        return sb.toString()
    }

    private fun smallKanji(g: Int): String = buildString {
        val units = listOf(1000 to "千", 100 to "百", 10 to "十")
        var r = g
        for ((v, u) in units) {
            val q = r / v
            if (q > 0) { if (q > 1) append(KANJI_DIGIT[q]); append(u) }
            r %= v
        }
        if (r > 0) append(KANJI_DIGIT[r])
    }

    /** 12000 → 1万2000（1万未満はそのまま）。 */
    private fun toMan(n: Long): String {
        if (n < 10000) return n.toString()
        val parts = mutableListOf<String>()
        var rest = n
        var group = 0
        while (rest > 0) {
            val g = rest % 10000
            if (g > 0) parts.add(0, "$g${BIG_UNIT[group]}")
            rest /= 10000
            group++
        }
        return parts.joinToString("")
    }

    /** 数字だけを打ったとき：漢数字・金額の書き方。 */
    fun numbers(preedit: String): List<String> {
        val t = digits(preedit).replace(",", "")
        if (t.isEmpty() || t.length > 16 || !t.all { it.isDigit() }) return emptyList()
        if (t.length > 1 && t[0] == '0') return emptyList()  // 電話番号や郵便番号っぽいもの
        val n = t.toLong()
        val comma = "%,d".format(n)
        val kanji = toKanji(n)
        val positional = t.map { KANJI_DIGIT[it - '0'] }.joinToString("")
        return listOf(comma, kanji, "¥$comma", "${comma}円", "${toMan(n)}円", "${kanji}円", positional)
            .distinct()
    }

    // ---- 単位 ----

    private val UNITS: List<Pair<String, List<String>>> = listOf(
        "きろめーとる" to listOf("{}km", "{}キロメートル"),
        "きろめとる" to listOf("{}km"),
        "きろぐらむ" to listOf("{}kg", "{}キログラム"),
        "きろ" to listOf("{}km", "{}kg", "{}キロ"),
        "めーとる" to listOf("{}m", "{}メートル"),
        "めとる" to listOf("{}m"),
        "せんちめーとる" to listOf("{}cm"),
        "せんち" to listOf("{}cm", "{}センチ"),
        "みりめーとる" to listOf("{}mm"),
        "みりりっとる" to listOf("{}mL", "{}ml"),
        "みり" to listOf("{}mm", "{}ミリ"),
        "ぐらむ" to listOf("{}g", "{}グラム"),
        "とん" to listOf("{}t", "{}トン"),
        "りっとる" to listOf("{}L", "{}ℓ", "{}リットル"),
        "へいべい" to listOf("{}㎡", "{}m²"),
        "へいほうめーとる" to listOf("{}㎡", "{}m²"),
        "りゅうべい" to listOf("{}㎥", "{}m³"),
        "ど" to listOf("{}℃", "{}°", "{}度"),
        "ぱーせんと" to listOf("{}%", "{}％"),
        "ぱーせんとう" to listOf("{}%"),
        "えん" to listOf("{}円", "¥{}"),
        "まんえん" to listOf("{}万円"),
        "どる" to listOf("\${}", "{}ドル"),
        "ゆーろ" to listOf("€{}", "{}ユーロ"),
        "じかん" to listOf("{}時間"),
        "じ" to listOf("{}時"),
        "ふん" to listOf("{}分"),
        "ぷん" to listOf("{}分"),
        "びょう" to listOf("{}秒"),
        "にち" to listOf("{}日"),
        "かげつ" to listOf("{}か月", "{}ヶ月", "{}カ月"),
        "しゅうかん" to listOf("{}週間"),
        "ねん" to listOf("{}年"),
        "さい" to listOf("{}歳", "{}才"),
        "ばい" to listOf("{}倍"),
        "こ" to listOf("{}個"),
        "にん" to listOf("{}人"),
        "かい" to listOf("{}回", "{}階"),
        "ばん" to listOf("{}番"),
        "まい" to listOf("{}枚"),
        "ほん" to listOf("{}本"),
        "ぽん" to listOf("{}本"),
        "ぼん" to listOf("{}本"),
        "ひき" to listOf("{}匹"),
        "ぴき" to listOf("{}匹"),
        "びき" to listOf("{}匹"),
        "さつ" to listOf("{}冊"),
        "だい" to listOf("{}台"),
        "けん" to listOf("{}件", "{}軒"),
        "ぎが" to listOf("{}GB", "{}ギガ"),
        "ぎがばいと" to listOf("{}GB"),
        "めが" to listOf("{}MB", "{}メガ"),
        "めがばいと" to listOf("{}MB"),
        "きろばいと" to listOf("{}KB"),
        "てら" to listOf("{}TB", "{}テラ"),
        "へるつ" to listOf("{}Hz"),
        "わっと" to listOf("{}W"),
        "ぼると" to listOf("{}V"),
        "あんぺあ" to listOf("{}A"),
        "かろりー" to listOf("{}kcal", "{}カロリー"),
        "きろかろりー" to listOf("{}kcal"),
        "いんち" to listOf("{}インチ", "{}inch"),
        "ぴくせる" to listOf("{}px"),
    )

    /** 数字＋単位の読み（5きろ・3ふん）→ 5km・5kg・3分 など。 */
    fun units(preedit: String): List<String> {
        val t = digits(preedit)
        val m = Regex("^(\\d+(?:\\.\\d+)?)([ぁ-ゖー]+)$").find(t) ?: return emptyList()
        val number = m.groupValues[1]
        val unit = m.groupValues[2]
        val forms = UNITS.firstOrNull { it.first == unit }?.second ?: return emptyList()
        return forms.map { it.replace("{}", number) }
    }

    /** 入力中の文字が「式＋＝」なら、答えの候補。 */
    fun calculate(preedit: String): List<String> {
        val text = normalize(preedit)
        if (text.length < 4 || !text.endsWith("=")) return emptyList()
        val expr = text.dropLast(1)
        if (!expr.any { it in "+-*/" } || !expr.all { it.isDigit() || it in "+-*/().%" }) return emptyList()
        val value = try { Parser(expr).parse() } catch (_: Exception) { return emptyList() }
        if (value.isNaN() || value.isInfinite()) return emptyList()
        val answer = format(value)
        return listOf(answer, "$text$answer")
    }

    private fun normalize(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                in '０'..'９' -> '0' + (c - '０')
                '＋' -> '+'; '－', 'ー', '−' -> '-'; '＊', '×' -> '*'; '／', '÷' -> '/'
                '＝' -> '='; '（' -> '('; '）' -> ')'; '．' -> '.'; '％' -> '%'; '，' -> ','
                else -> c
            }
        )
    }

    /** 全角の数字と記号だけ半角に（「ー」はそのまま：めーとる が め-とる にならないように）。 */
    private fun digits(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                in '０'..'９' -> '0' + (c - '０')
                '．' -> '.'; '，' -> ','; '／' -> '/'
                else -> c
            }
        )
    }

    private fun format(v: Double): String {
        val r = Math.round(v * 1e10) / 1e10
        return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString()
        else r.toBigDecimal().stripTrailingZeros().toPlainString()
    }

    /** かんたんな計算機（+ - * / と かっこ）。 */
    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = sum(); if (i != s.length) error("rest"); return v }
        fun sum(): Double {
            var v = product()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = product(); v = if (op == '+') v + r else v - r }
            return v
        }
        fun product(): Double {
            var v = unary()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = unary(); v = if (op == '*') v * r else v / r }
            return v
        }
        fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else atom()
        fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = sum(); if (i >= s.length || s[i] != ')') error(")"); i++; return pct(v) }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) error("num")
            return pct(s.substring(start, i).toDouble())
        }
        fun pct(v: Double): Double = if (i < s.length && s[i] == '%') { i++; v / 100.0 } else v
    }
}

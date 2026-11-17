package io.github.soichi11208.zinna.ime

import java.util.Calendar

/**
 * 変換候補に足す「便利な候補」。ネットは使わず、スマホの中だけで作る。
 *
 *  きょう → 2026/11/17・11月17日・2026年11月17日(火) …
 *  いま   → 10:25・10時25分
 *  12*3=  → 36・12*3=36
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
            "きのう", "さくじつ" -> day(-1)
            "おととい" -> day(-2)
            "いま", "じこく", "げんざい" -> time
            "にちじ" -> listOf(day(0)[0] + " " + time[0], day(0)[1] + " " + time[1])
            "ようび" -> listOf(WEEK[now.get(Calendar.DAY_OF_WEEK) - 1] + "曜日")
            else -> emptyList()
        }
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
                '＝' -> '='; '（' -> '('; '）' -> ')'; '．' -> '.'; '％' -> '%'
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

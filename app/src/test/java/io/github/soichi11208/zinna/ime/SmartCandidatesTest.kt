package io.github.soichi11208.zinna.ime

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCandidatesTest {
    @Test
    fun calculates() {
        assertEquals(listOf("36", "12*3=36"), SmartCandidates.calculate("12*3="))
        assertEquals("36", SmartCandidates.calculate("１２＊３＝").first())
        assertEquals("2.5", SmartCandidates.calculate("5/2=").first())
        assertEquals("14", SmartCandidates.calculate("2+3*4=").first())
        assertTrue(SmartCandidates.calculate("123=").isEmpty())
        assertTrue(SmartCandidates.calculate("あ=").isEmpty())
    }

    @Test
    fun dates() {
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 17, 10, 5) }
        assertEquals("2026/11/17", SmartCandidates.dateTime("きょう", c).first())
        assertEquals("11月18日", SmartCandidates.dateTime("あした", c)[1])
        assertEquals("10:05", SmartCandidates.dateTime("いま", c).first())
        assertTrue(SmartCandidates.dateTime("ねこ", c).isEmpty())
    }

    @Test
    fun numbers() {
        val n = SmartCandidates.numbers("12000")
        assertTrue("一万二千" in n)
        assertTrue("¥12,000" in n)
        assertTrue("12,000円" in n)
        assertTrue("1万2000円" in n)
        assertEquals("千二百三十四", SmartCandidates.toKanji(1234))
        assertEquals("一億十", SmartCandidates.toKanji(100000010))
        assertEquals("十", SmartCandidates.toKanji(10))
        assertTrue(SmartCandidates.numbers("090").isEmpty())
        assertTrue(SmartCandidates.numbers("12a").isEmpty())
    }

    @Test
    fun weekdays() {
        // 2026/11/25 は水曜日
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 25, 10, 0) }
        assertEquals("11/27(金)", SmartCandidates.dateWithWeekday("11/27", c).first())
        assertEquals("2026/11/27(金)", SmartCandidates.dateWithWeekday("2026/11/27", c).first())
        assertEquals("11/27(金)", SmartCandidates.dateWithWeekday("１１がつ２７にち", c).first())
        assertTrue(SmartCandidates.dateWithWeekday("2/30", c).isEmpty())
        assertEquals("11/27(金)", SmartCandidates.dateTime("きんようび", c).first())
        assertEquals("11/25(水)", SmartCandidates.dateTime("すいようび", c).first())
        assertEquals("12/4(金)", SmartCandidates.dateTime("らいしゅうのきんようび", c).first())
        assertEquals("11/23(月)", SmartCandidates.dateTime("こんしゅうのげつようび", c).first())
        assertEquals("11/29(日)", SmartCandidates.dateTime("こんしゅうのにちようび", c).first())
        assertEquals("11/16(月)", SmartCandidates.dateTime("せんしゅうのげつようび", c).first())
        assertEquals("11/28(土)", SmartCandidates.dateTime("しあさって", c)[3])
        assertTrue("令和9年" in SmartCandidates.dateTime("らいねん", c))
        assertTrue("2026年12月" in SmartCandidates.dateTime("らいげつ", c))
        assertTrue(SmartCandidates.dateTime("かよう", c).isEmpty())
    }

    @Test
    fun units() {
        assertEquals(listOf("5km", "5kg", "5キロ"), SmartCandidates.units("5きろ"))
        assertEquals("3分", SmartCandidates.units("３ふん").first())
        assertEquals("100m", SmartCandidates.units("100めーとる").first())
        assertEquals("$20", SmartCandidates.units("20どる").first())
        assertTrue(SmartCandidates.units("きろ").isEmpty())
        assertTrue(SmartCandidates.units("5ねこ").isEmpty())
    }
}

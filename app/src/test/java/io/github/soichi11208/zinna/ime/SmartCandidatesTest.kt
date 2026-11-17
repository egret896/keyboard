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
}

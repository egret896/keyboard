package io.github.soichi11208.zinna.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZipCodesTest {
    @Test
    fun recognizesCodes() {
        assertEquals("1000001", ZipCodes.codeOf("1000001"))
        assertEquals("1000001", ZipCodes.codeOf("100-0001"))
        assertEquals("1000001", ZipCodes.codeOf("１００ー０００１"))
        assertNull(ZipCodes.codeOf("10000012"))
        assertNull(ZipCodes.codeOf("100-00a1"))
    }
}

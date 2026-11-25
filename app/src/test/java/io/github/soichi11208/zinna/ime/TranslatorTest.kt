package io.github.soichi11208.zinna.ime

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    // gen_translate_dict.py と同じ並び（「言葉＋タブ」のバイト順）
    private val lines = listOf(
        "かみ\tpaper\tgod", "かみさま\tgod", "たべる\teat\tlive on", "ねこ\tcat", "猫\tcat", "食べる\teat",
    ).sortedWith { a, b ->
        java.util.Arrays.compareUnsigned(
            (a.substringBefore('\t') + "\t").toByteArray(),
            (b.substringBefore('\t') + "\t").toByteArray(),
        )
    }
    private val buf = ByteBuffer.wrap(lines.joinToString("\n", postfix = "\n").toByteArray())

    @Test
    fun findsEveryLine() {
        assertEquals(listOf("paper", "god"), Translator.search(buf, "かみ"))
        assertEquals(listOf("god"), Translator.search(buf, "かみさま"))
        assertEquals(listOf("eat", "live on"), Translator.search(buf, "たべる"))
        assertEquals(listOf("cat"), Translator.search(buf, "ねこ"))
        assertEquals(listOf("cat"), Translator.search(buf, "猫"))
        assertEquals(listOf("eat"), Translator.search(buf, "食べる"))
    }

    @Test
    fun missesCleanly() {
        assertTrue(Translator.search(buf, "か").isEmpty())
        assertTrue(Translator.search(buf, "いぬ").isEmpty())
        assertTrue(Translator.search(buf, "龍").isEmpty())
    }
}

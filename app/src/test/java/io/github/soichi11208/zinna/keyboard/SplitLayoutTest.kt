package io.github.soichi11208.zinna.keyboard

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class SplitLayoutTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(id: String): KeyboardLayout {
        val dir = listOf(File("src/main/assets/layouts"), File("app/src/main/assets/layouts"))
            .first { it.isDirectory }
        return json.decodeFromString(KeyboardLayout.serializer(), File(dir, "$id.json").readText())
    }

    @Test
    fun kanaBecomesTheSameKeyboardTwiceSideBySide() {
        val original = load("flick_kana")
        val split = SplitLayout.split(original)
        val first = split.rows[0].keys.map { it.faceLabel }
        assertEquals(listOf("↶", "あ", "か", "さ", "⌫", "↶", "あ", "か", "さ", "⌫"), first)
        split.rows.forEachIndexed { i, row ->
            assertEquals(10, row.keys.size)
            assertEquals(original.rows[i].keys, row.keys.takeLast(5))
            assertEquals(0.6f, row.keys[4].gapAfter)
        }
    }

    @Test
    fun qwertyIsLeftAlone() {
        val qwerty = load("qwerty_kana")
        assertSame(qwerty, SplitLayout.split(qwerty))
    }
}

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
    fun kanaSplitsIntoTwoMirroredHalvesWithFunctionKeysInTheMiddle() {
        val split = SplitLayout.split(load("flick_kana"))
        val first = split.rows[0].keys.map { it.faceLabel }
        assertEquals(listOf("あ", "か", "さ", "↶", "⌫", "あ", "か", "さ"), first)
        for (row in split.rows) {
            assertEquals(8, row.keys.size)
            assertEquals(row.keys.take(3), row.keys.takeLast(3))
        }
    }

    @Test
    fun qwertyIsLeftAlone() {
        val qwerty = load("qwerty_kana")
        assertSame(qwerty, SplitLayout.split(qwerty))
    }
}

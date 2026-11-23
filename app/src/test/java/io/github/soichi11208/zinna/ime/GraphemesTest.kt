package io.github.soichi11208.zinna.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemesTest {

    private fun last(s: String) = Graphemes.lastLength(s)

    @Test fun plainCharacters() {
        assertEquals(0, last(""))
        assertEquals(1, last("あいう"))
        assertEquals(1, last("abc"))
    }

    @Test fun surrogatePairIsOneCharacter() {
        assertEquals(2, last("あ😀"))
    }

    @Test fun skinToneStaysWithItsEmoji() {
        assertEquals(4, last("あ👍🏽"))
    }

    @Test fun variationSelectorStaysWithItsEmoji() {
        assertEquals(2, last("あ❤️"))
    }

    @Test fun zwjFamilyIsOneCharacter() {
        val family = "👨‍👩‍👧"
        assertEquals(family.length, last("あ$family"))
    }

    @Test fun zwjWithSkinToneAndSelector() {
        val e = "🧑🏽‍❤️‍💋‍🧑🏻"
        assertEquals(e.length, last("x$e"))
    }

    @Test fun flagIsTwoRegionalIndicators() {
        assertEquals(4, last("あ🇯🇵"))
        assertEquals(4, last("🇯🇵🇺🇸"))
    }

    @Test fun keycapIsOneCharacter() {
        assertEquals(3, last("a1️⃣"))
    }

    @Test fun combiningDakutenStaysWithKana() {
        assertEquals(2, last("あが"))
    }

    @Test fun crlfIsOne() {
        assertEquals(2, last("a\r\n"))
    }

    @Test fun moveByCount() {
        val s = "a👍🏽b"
        assertEquals(1, Graphemes.firstLength(s, 1))
        assertEquals(5, Graphemes.firstLength(s, 2))
        assertEquals(1, Graphemes.lastLength(s, 1))
        assertEquals(5, Graphemes.lastLength(s, 2))
        assertEquals(s.length, Graphemes.lastLength(s, 10))
        assertEquals(s.length, Graphemes.firstLength(s, 10))
    }
}

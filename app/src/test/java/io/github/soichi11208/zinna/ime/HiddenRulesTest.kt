package io.github.soichi11208.zinna.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenRulesTest {
    @Test fun derivesChangedPartWithContext() {
        val rule = HiddenRules.derive("それはなんなん", "それは南なん")!!
        assertEquals(HiddenRules.Rule("なんな", "南な"), rule)
        assertTrue(rule.matches("それはなんなんやろ", "それは南なんやろ"))
        assertTrue(rule.matches("なんなん", "南なん"))
        assertFalse(rule.matches("なんきょく", "南極"))
        assertFalse(rule.matches("それはなんなん", "それはなんなん"))
    }

    @Test fun shorterHiddenAlsoCoversLonger() {
        val rule = HiddenRules.derive("なんなん", "南なん")!!
        assertTrue(rule.matches("それはなんなん", "それは南なん"))
    }

    @Test fun encodeRoundTrip() {
        val rule = HiddenRules.Rule("なんな", "南な")
        assertEquals(rule, HiddenRules.decode(rule.encode()))
    }
}

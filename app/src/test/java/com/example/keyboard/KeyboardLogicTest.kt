package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SuggestionEngineTest {
    private val engine = SuggestionEngine(listOf("hello", "help", "helmet", "world"))

    @Test
    fun builtInWordsMatchPrefix() {
        assertEquals(listOf("hello", "help", "helmet"), engine.suggest("hel"))
    }

    @Test
    fun personalWordsComeFirst() {
        val out = engine.suggest("hel", mapOf("helsinki" to 5))
        assertEquals("helsinki", out.first())
    }

    @Test
    fun emptyPrefixGivesDefaults() {
        assertEquals(SuggestionEngine.DEFAULTS, engine.suggest(""))
    }

    @Test
    fun exactWordIsNotSuggestedAgain() {
        assertFalse(engine.suggest("hello").contains("hello"))
    }
}

class StatsMathTest {
    @Test
    fun halfABookIsHalf() {
        assertEquals(0.5, StatsMath.books(40_000L), 0.0001)
    }

    @Test
    fun zeroWordsIsZeroBooks() {
        assertEquals(0.0, StatsMath.books(0L), 0.0001)
    }
}

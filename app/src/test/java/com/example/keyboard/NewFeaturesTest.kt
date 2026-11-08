package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanUrduTest {
    @Test
    fun commonWordsAreFound() {
        assertEquals("کیا", RomanUrdu.lookup("kya"))
        assertEquals("شکریہ", RomanUrdu.lookup("Shukriya"))
    }

    @Test
    fun unknownWordsAreNotGuessed() {
        assertNull(RomanUrdu.lookup("hello"))
        assertNull(RomanUrdu.lookup("zzzzzz"))
    }

    @Test
    fun dictionaryIsBigEnough() {
        assertTrue(RomanUrdu.size >= 600)
    }

    @Test
    fun completesPrefixesOfThreeOrMoreLetters() {
        assertTrue(RomanUrdu.complete("shuk", 3).contains("shukriya"))
        assertTrue(RomanUrdu.complete("sh", 3).isEmpty())
    }
}

class EmojiSearchTest {
    @Test
    fun findsPizza() {
        assertTrue(EmojiData.search("pizza").contains("🍕"))
    }

    @Test
    fun findsByWordStart() {
        assertTrue(EmojiData.search("laug").isNotEmpty())
    }

    @Test
    fun emptyQueryGivesNothing() {
        assertTrue(EmojiData.search("   ").isEmpty())
    }

    @Test
    fun everyWordMustMatch() {
        assertTrue(EmojiData.search("pizza zzzz").isEmpty())
    }
}

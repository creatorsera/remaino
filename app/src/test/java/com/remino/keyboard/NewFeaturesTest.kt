package com.remino.keyboard

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

class CaretDeleteTest {
    @Test
    fun deletesWholeWordAroundCaret() {
        assertEquals(3 to 2, Editing.wordDeleteCounts("hello wor", "ld there"))
    }

    @Test
    fun deletesWordBeforeWhenCaretIsAtEnd() {
        assertEquals(5 to 0, Editing.wordDeleteCounts("hello world", ""))
    }

    @Test
    fun deletesWordAfterWhenCaretIsAfterASpace() {
        assertEquals(0 to 5, Editing.wordDeleteCounts("hello ", "world there"))
    }

    @Test
    fun deletesNothingOnWhitespace() {
        assertEquals(0 to 0, Editing.wordDeleteCounts("hello ", " there"))
        assertEquals(0 to 0, Editing.wordDeleteCounts("", ""))
    }

    @Test
    fun keepsApostrophesAndHyphensInsideWords() {
        assertEquals(5 to 0, Editing.wordDeleteCounts("don't", ""))
        assertEquals(5 to 0, Editing.wordDeleteCounts("well-", ""))
    }

    @Test
    fun countsSurrogatePairsAsOneCharacter() {
        assertEquals(2, Editing.firstCodePointLength("😀 rest"))
        assertEquals(1, Editing.firstCodePointLength("a rest"))
        assertEquals(0, Editing.firstCodePointLength(""))
    }
}

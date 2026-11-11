package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The shipped word lists must actually cover countries, long words and Urdu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryCoverageTest {

    @Test
    fun englishDictionaryIncludesCountriesAndLongWords() {
        val words = EnglishWords.load(RuntimeEnvironment.getApplication())
        assertTrue("dictionary is tiny: ${words.size}", words.size > 30_000)
        assertTrue(words.contains("pakistan"))
        assertTrue(words.contains("bangladesh"))
        assertTrue(words.contains("afghanistan"))
        assertTrue(words.contains("international"))
        assertTrue(words.any { it.length >= 18 })
    }

    @Test
    fun countryNamesAreSuggestedFromPrefix() {
        val engine = SuggestionEngine(EnglishWords.load(RuntimeEnvironment.getApplication()))
        assertTrue(engine.suggest("pakis").any { it == "pakistan" })
        assertTrue(engine.suggest("bangla").any { it == "bangladesh" })
    }

    @Test
    fun romanUrduCoversCountriesAndMore() {
        assertEquals("پاکستان", RomanUrdu.lookup("pakistan"))
        assertEquals("انڈیا", RomanUrdu.lookup("india"))
        assertTrue(RomanUrdu.size > 1_000)
    }

    @Test
    fun emojiTrayIncludesEveryGroupIncludingFlags() {
        assertTrue(EmojiData.categories.size >= 9)
        assertTrue(EmojiData.categories.any { it.name == "Flags" })
        assertTrue(EmojiData.categories.sumOf { it.emojis.size } > 1_800)
    }
}

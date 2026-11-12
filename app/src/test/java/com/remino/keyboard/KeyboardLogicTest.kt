package com.remino.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun hiddenWordsAreNotSuggested() {
        assertFalse(engine.suggest("hel", hidden = setOf("help")).contains("help"))
    }

    @Test
    fun hiddenDefaultsAreSkipped() {
        assertFalse(engine.suggest("", hidden = setOf("the")).contains("the"))
    }
}

class EditingTest {
    @Test
    fun trailingWordPlusSpaceIsRemovedTogether() {
        assertEquals(6, Editing.trailingTokenLength("hello world "))
    }

    @Test
    fun trailingWordWithoutSpaceStillCounts() {
        assertEquals(5, Editing.trailingTokenLength("hello world"))
    }

    @Test
    fun multipleSpacesAreIncluded() {
        assertEquals(7, Editing.trailingTokenLength("hello  "))
    }

    @Test
    fun newlinesAreIncluded() {
        assertEquals(6, Editing.trailingTokenLength("hello\n"))
    }

    @Test
    fun emptyTextHasNothingToRemove() {
        assertEquals(0, Editing.trailingTokenLength(""))
    }

    @Test
    fun whitespaceOnlyTextCountsAsTrailingSpace() {
        assertEquals(3, Editing.trailingTokenLength("   "))
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

    @Test
    fun streakCountsConsecutiveDays() {
        assertEquals(3, StatsMath.streak(listOf(5L, 2L, 9L, 0L, 4L)))
    }

    @Test
    fun quietTodayDoesNotBreakStreak() {
        assertEquals(2, StatsMath.streak(listOf(0L, 7L, 3L, 0L)))
    }

    @Test
    fun noWordsMeansNoStreak() {
        assertEquals(0, StatsMath.streak(listOf(0L, 0L, 0L)))
    }

    @Test
    fun averageHandlesZeroDays() {
        assertEquals(0L, StatsMath.average(100L, 0))
        assertEquals(50L, StatsMath.average(100L, 2))
    }
}

class LayoutsTest {
    @Test
    fun rowCountsMatchPages() {
        for (numberRow in listOf(true, false)) {
            assertEquals(Layouts.rowCount(numberRow), Layouts.letters(numberRow).size)
            assertEquals(Layouts.rowCount(numberRow), Layouts.symbols(numberRow).size)
        }
    }

    @Test
    fun everyRowFillsTheWidth() {
        val pages = listOf(
            Layouts.letters(true), Layouts.letters(false), Layouts.symbols(true), Layouts.symbols(false),
        )
        for (page in pages) {
            for (row in page) {
                assertEquals(10.0, row.sumOf { it.weight.toDouble() }, 0.001)
            }
        }
    }
}

class BackupCryptoTest {
    private val iterations = 10_000

    @Test
    fun roundTripWorks() {
        val password = "correct horse".toCharArray()
        val encrypted = BackupCrypto.encrypt("{\"hello\":\"world\"}", password, iterations)
        assertEquals("{\"hello\":\"world\"}", BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()))
    }

    @Test
    fun wrongPasswordIsRejected() {
        val encrypted = BackupCrypto.encrypt("secret", "right-password".toCharArray(), iterations)
        try {
            BackupCrypto.decrypt(encrypted, "wrong-password".toCharArray())
            fail("Expected the wrong password to be rejected")
        } catch (e: BackupException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun otherFilesAreRejected() {
        try {
            BackupCrypto.decrypt(ByteArray(100) { 7 }, "whatever1".toCharArray())
            fail("Expected a non-backup file to be rejected")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("backup"))
        }
    }
}

class ClipboardLogicTest {
    private val hour = 3_600_000L

    @Test
    fun newestComesFirstAndDuplicatesMove() {
        var items = ClipboardLogic.add(emptyList(), "a", 1L)
        items = ClipboardLogic.add(items, "b", 2L)
        items = ClipboardLogic.add(items, "a", 3L)
        assertEquals(listOf("a", "b"), items.map { it.text })
    }

    @Test
    fun duplicateKeepsItsPin() {
        var items = ClipboardLogic.add(emptyList(), "a", 1L)
        items = ClipboardLogic.togglePin(items, "a")
        items = ClipboardLogic.add(items, "a", 5L)
        assertTrue(items.single().pinned)
    }

    @Test
    fun pinnedItemsNeverExpire() {
        val items = listOf(ClipItem("old", true, 0L), ClipItem("older", false, 0L))
        val kept = ClipboardLogic.prune(items, 48 * hour, 24)
        assertEquals(listOf("old"), kept.map { it.text })
    }

    @Test
    fun zeroHoursMeansNeverExpire() {
        val items = listOf(ClipItem("x", false, 0L))
        assertEquals(1, ClipboardLogic.prune(items, 1000 * hour, 0).size)
    }

    @Test
    fun listIsTrimmedButPinnedStay() {
        var items = listOf(ClipItem("keep", true, 0L))
        for (i in 1..60) items = ClipboardLogic.add(items, "item$i", i.toLong())
        assertEquals(ClipboardLogic.MAX_UNPINNED + 1, items.size)
        assertTrue(items.any { it.text == "keep" })
    }

    @Test
    fun clearUnpinnedKeepsPinned() {
        val items = listOf(ClipItem("a", true, 1L), ClipItem("b", false, 2L))
        assertEquals(listOf("a"), ClipboardLogic.clearUnpinned(items).map { it.text })
    }
}

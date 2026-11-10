package com.example.keyboard

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorrectionsTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun remembersAndReplaysACorrection() {
        val store = Corrections(context())
        store.clear()
        assertTrue(store.record("teh", "the"))
        assertEquals("the", store.correctionFor("teh"))
        assertEquals("the", store.correctionFor("Teh"))
    }

    @Test
    fun ignoresShortOrIdenticalWords() {
        val store = Corrections(context())
        store.clear()
        assertEquals(false, store.record("te", "the"))
        assertEquals(false, store.record("the", "the"))
        assertEquals(0, store.count())
    }

    @Test
    fun forgetsOneAndClearsAll() {
        val store = Corrections(context())
        store.clear()
        store.record("teh", "the")
        store.record("recieve", "receive")
        assertEquals(2, store.count())
        store.forget("teh")
        assertNull(store.correctionFor("teh"))
        assertEquals(1, store.count())
        store.clear()
        assertEquals(0, store.count())
    }
}

class SuggestionEngineTypoTest {
    private val engine = SuggestionEngine(emptyList())

    @Test
    fun oneEditDistanceIsDetected() {
        assertTrue(engine.withinOneEdit("teh", "the"))
        assertTrue(engine.withinOneEdit("helo", "hello"))
        assertTrue(engine.withinOneEdit("wrold", "world"))
        assertTrue(!engine.withinOneEdit("cat", "dog"))
        assertTrue(!engine.withinOneEdit("hello", "helping"))
    }

    @Test
    fun learnedCorrectionComesFirst() {
        val engine = SuggestionEngine(listOf("the", "there", "them"))
        val out = engine.suggest("teh", corrections = mapOf("teh" to "the"))
        assertEquals("the", out.first())
    }

    @Test
    fun nearMissesFillAnOtherwiseEmptyBar() {
        val engine = SuggestionEngine(listOf("hello", "world"))
        assertEquals("hello", engine.suggest("helo").first())
    }
}

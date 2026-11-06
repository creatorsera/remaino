package com.example.keyboard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StatsMath {
    private const val WORDS_PER_BOOK = 80_000.0

    fun books(totalWords: Long): Double = totalWords / WORDS_PER_BOOK
}

/** Stores only numbers (words typed today and in total). Never stores what you wrote. */
class TypingStats(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("typing_stats", Context.MODE_PRIVATE)

    private fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun addWord() {
        val today = dayKey()
        val sameDay = prefs.getString(KEY_DAY, null) == today
        val todayCount = if (sameDay) prefs.getLong(KEY_TODAY, 0L) else 0L
        prefs.edit()
            .putString(KEY_DAY, today)
            .putLong(KEY_TODAY, todayCount + 1)
            .putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0L) + 1)
            .apply()
    }

    fun todayWords(): Long =
        if (prefs.getString(KEY_DAY, null) == dayKey()) prefs.getLong(KEY_TODAY, 0L) else 0L

    fun totalWords(): Long = prefs.getLong(KEY_TOTAL, 0L)

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_TODAY = "today"
        const val KEY_TOTAL = "total"
    }
}

/** How often each word was used (counts only), kept on the device to improve suggestions. */
class WordFrequencies(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("word_frequencies", Context.MODE_PRIVATE)
    private val counts = HashMap<String, Int>()

    init {
        for ((word, value) in prefs.all) {
            if (value is Int) counts[word] = value
        }
    }

    fun snapshot(): Map<String, Int> = counts

    fun increment(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.length > 24 || !w.all { it.isLetter() || it == '\'' }) return
        if (counts.size >= MAX_WORDS && !counts.containsKey(w)) return
        val next = (counts[w] ?: 0) + 1
        counts[w] = next
        prefs.edit().putInt(w, next).apply()
    }

    fun clear() {
        counts.clear()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val MAX_WORDS = 5000
    }
}

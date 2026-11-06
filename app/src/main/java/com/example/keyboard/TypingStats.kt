package com.example.keyboard

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object StatsMath {
    private const val WORDS_PER_BOOK = 80_000.0

    fun books(totalWords: Long): Double = totalWords / WORDS_PER_BOOK

    /** counts[0] is today, counts[1] is yesterday, and so on. A quiet today does not break the streak yet. */
    fun streak(counts: List<Long>): Int {
        var i = if (counts.isNotEmpty() && counts[0] == 0L) 1 else 0
        var n = 0
        while (i < counts.size && counts[i] > 0L) {
            n++
            i++
        }
        return n
    }

    fun average(totalWords: Long, activeDays: Int): Long =
        if (activeDays <= 0) 0L else totalWords / activeDays
}

/** Stores only numbers (words typed per day). Never stores what you wrote. */
class TypingStats(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("typing_stats", Context.MODE_PRIVATE)

    private fun dayKey(daysAgo: Int = 0): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    @Synchronized
    fun addWord() {
        val today = dayKey()
        val editor = prefs.edit()
        editor.putLong(DAY_PREFIX + today, prefs.getLong(DAY_PREFIX + today, 0L) + 1)
        editor.putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0L) + 1)
        if (!prefs.contains(KEY_INSTALL)) editor.putString(KEY_INSTALL, today)
        editor.apply()
    }

    fun todayWords(): Long = prefs.getLong(DAY_PREFIX + dayKey(), 0L)

    fun totalWords(): Long = prefs.getLong(KEY_TOTAL, 0L)

    /** Index 0 is today. */
    fun lastDays(n: Int): List<Long> = (0 until n).map { prefs.getLong(DAY_PREFIX + dayKey(it), 0L) }

    fun streak(): Int = StatsMath.streak(lastDays(365))

    fun bestDay(): Long {
        var best = 0L
        for ((k, v) in prefs.all) {
            if (k.startsWith(DAY_PREFIX) && v is Long && v > best) best = v
        }
        return best
    }

    fun activeDays(): Int {
        var n = 0
        for ((k, v) in prefs.all) {
            if (k.startsWith(DAY_PREFIX) && v is Long && v > 0L) n++
        }
        return n
    }

    fun averagePerActiveDay(): Long = StatsMath.average(totalWords(), activeDays())

    fun reset() {
        prefs.edit().clear().apply()
    }

    fun exportData(): JSONObject {
        val days = JSONObject()
        for ((k, v) in prefs.all) {
            if (k.startsWith(DAY_PREFIX) && v is Long) days.put(k.removePrefix(DAY_PREFIX), v)
        }
        return JSONObject()
            .put("total", totalWords())
            .put("installDay", prefs.getString(KEY_INSTALL, "").orEmpty())
            .put("days", days)
    }

    fun importData(json: JSONObject) {
        val editor = prefs.edit()
        editor.clear()
        editor.putLong(KEY_TOTAL, json.optLong("total", 0L))
        val install = json.optString("installDay", "")
        if (install.isNotEmpty()) editor.putString(KEY_INSTALL, install)
        val days = json.optJSONObject("days")
        if (days != null) {
            val keys = days.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                editor.putLong(DAY_PREFIX + k, days.optLong(k, 0L))
            }
        }
        editor.apply()
    }

    private companion object {
        const val DAY_PREFIX = "d_"
        const val KEY_TOTAL = "total"
        const val KEY_INSTALL = "install_day"
    }
}

/** How often each word was used (counts only), kept on the device to improve suggestions. */
class WordFrequencies private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("word_frequencies", Context.MODE_PRIVATE)
    private val counts = HashMap<String, Int>()

    init {
        for ((word, value) in prefs.all) {
            if (value is Int) counts[word] = value
        }
    }

    fun snapshot(): Map<String, Int> = counts

    fun top(n: Int): List<Pair<String, Int>> =
        counts.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

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

    fun exportData(): JSONObject {
        val obj = JSONObject()
        for ((w, c) in counts) obj.put(w, c)
        return obj
    }

    fun importData(json: JSONObject) {
        counts.clear()
        val editor = prefs.edit()
        editor.clear()
        val keys = json.keys()
        while (keys.hasNext()) {
            val w = keys.next()
            if (counts.size >= MAX_WORDS) break
            val c = json.optInt(w, 0)
            if (c > 0) {
                counts[w] = c
                editor.putInt(w, c)
            }
        }
        editor.apply()
    }

    companion object {
        private const val MAX_WORDS = 5000

        @Volatile
        private var instance: WordFrequencies? = null

        fun get(context: Context): WordFrequencies =
            instance ?: synchronized(this) {
                instance ?: WordFrequencies(context.applicationContext).also { instance = it }
            }
    }
}

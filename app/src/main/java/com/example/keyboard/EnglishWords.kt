package com.example.keyboard

import android.content.Context

/**
 * The built-in English dictionary, kept in assets so the code stays readable.
 * It is only read once per keyboard process.
 */
object EnglishWords {
    private const val ASSET = "english_words.txt"
    private const val MAX_WORDS = 12_000

    @Volatile
    private var cached: List<String>? = null

    fun load(context: Context): List<String> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: read(context).also { cached = it }
        }
    }

    private fun read(context: Context): List<String> {
        val words = ArrayList<String>(MAX_WORDS)
        try {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (words.size >= MAX_WORDS) break
                    val word = line.trim().lowercase()
                    if (word.length < 2 || word.length > 24) continue
                    if (!word.all { it in 'a'..'z' }) continue
                    if (SuggestionFilter.isBlocked(word)) continue
                    words.add(word)
                }
            }
        } catch (e: Exception) {
            return SuggestionEngine.COMMON_WORDS
        }
        return if (words.size >= 100) words else SuggestionEngine.COMMON_WORDS
    }
}

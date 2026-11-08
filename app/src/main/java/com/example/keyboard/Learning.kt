package com.example.keyboard

import android.content.Context

/**
 * Word pairs learned on the device. Only counts are stored; the words themselves
 * never leave the phone. Used for next-word suggestions.
 */
class Bigrams(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("word_bigrams", Context.MODE_PRIVATE)
    private val counts = HashMap<String, Int>()
    private val separator = '\u0001'

    init {
        for ((key, value) in prefs.all) {
            if (value is Int) counts[key] = value
        }
    }

    fun add(previous: String, next: String) {
        val p = clean(previous)
        val n = clean(next)
        if (p.isEmpty() || n.isEmpty() || p == n) return
        val key = "$p$separator$n"
        if (counts.size >= MAX_PAIRS && !counts.containsKey(key)) return
        val value = (counts[key] ?: 0) + 1
        counts[key] = value
        prefs.edit().putInt(key, value).apply()
    }

    /** Words that have followed [previous], most frequent first. */
    fun followers(previous: String, limit: Int = 3): List<String> {
        val p = clean(previous)
        if (p.isEmpty()) return emptyList()
        val prefix = "$p$separator"
        return counts.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .map { it.key.substring(prefix.length) }
            .filter { it.isNotEmpty() }
            .take(limit)
            .toList()
    }

    fun clear() {
        counts.clear()
        prefs.edit().clear().apply()
    }

    private fun clean(word: String): String {
        val w = word.trim().lowercase()
        if (w.length < 1 || w.length > 24) return ""
        return if (w.all { it.isLetter() || it == '\'' }) w else ""
    }

    private companion object {
        const val MAX_PAIRS = 6000
    }
}

/**
 * A small on-device map from words to emoji. Deliberately tiny: it should feel
 * like a hint, not a second keyboard.
 */
object EmojiPredict {
    private val map = mapOf(
        "love" to "\u2764\uFE0F", "like" to "\uD83D\uDC4D", "happy" to "\uD83D\uDE04",
        "sad" to "\uD83D\uDE22", "laugh" to "\uD83D\uDE02", "lol" to "\uD83D\uDE02",
        "cry" to "\uD83D\uDE2D", "angry" to "\uD83D\uDE20", "tired" to "\uD83D\uDE34",
        "sleep" to "\uD83D\uDE34", "good" to "\uD83D\uDC4D", "great" to "\uD83D\uDC4D",
        "nice" to "\uD83D\uDC4C", "yes" to "\uD83D\uDC4D", "no" to "\uD83D\uDC4E",
        "ok" to "\uD83D\uDC4C", "okay" to "\uD83D\uDC4C", "thanks" to "\uD83D\uDE4F",
        "thank" to "\uD83D\uDE4F", "sorry" to "\uD83D\uDE4F", "hello" to "\uD83D\uDC4B",
        "hi" to "\uD83D\uDC4B", "bye" to "\uD83D\uDC4B", "morning" to "\u2600\uFE0F",
        "night" to "\uD83C\uDF19", "today" to "\uD83D\uDCC5", "tomorrow" to "\uD83D\uDCC5",
        "food" to "\uD83C\uDF5F", "eat" to "\uD83C\uDF5F", "hungry" to "\uD83C\uDF5F",
        "coffee" to "\u2615", "tea" to "\u2615", "water" to "\uD83D\uDCA7",
        "pizza" to "\uD83C\uDF55", "cake" to "\uD83C\uDF70", "fire" to "\uD83D\uDD25",
        "party" to "\uD83C\uDF89", "birthday" to "\uD83C\uDF82", "gift" to "\uD83C\uDF81",
        "money" to "\uD83D\uDCB0", "work" to "\uD83D\uDCBC", "office" to "\uD83C\uDFE2",
        "home" to "\uD83C\uDFE0", "house" to "\uD83C\uDFE0", "car" to "\uD83D\uDE97",
        "bus" to "\uD83D\uDE8C", "train" to "\uD83D\uDE86", "plane" to "\u2708\uFE0F",
        "travel" to "\u2708\uFE0F", "phone" to "\uD83D\uDCF1", "call" to "\uD83D\uDCDE",
        "message" to "\uD83D\uDCAC", "time" to "\u23F0", "wait" to "\u23F3",
        "hot" to "\uD83D\uDD25", "cold" to "\u2744\uFE0F", "rain" to "\uD83C\uDF27\uFE0F",
        "sun" to "\u2600\uFE0F", "moon" to "\uD83C\uDF19", "star" to "\u2B50",
        "heart" to "\u2764\uFE0F", "kiss" to "\uD83D\uDE18", "hug" to "\uD83E\uDD17",
        "dog" to "\uD83D\uDC36", "cat" to "\uD83D\uDC31", "music" to "\uD83C\uDFB5",
        "movie" to "\uD83C\uDFAC", "game" to "\uD83C\uDFAE", "book" to "\uD83D\uDCDA",
        "study" to "\uD83D\uDCDA", "school" to "\uD83C\uDFEB", "doctor" to "\uD83D\uDC69\u200D\u2695\uFE0F",
        "sick" to "\uD83E\uDD12", "hospital" to "\uD83C\uDFE5", "congrats" to "\uD83C\uDF89",
        "wow" to "\uD83D\uDE2E", "cool" to "\uD83D\uDE0E", "perfect" to "\uD83D\uDC4C",
    )

    fun forWord(word: String): String? {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return null
        return map[w]
    }
}

/** A small, deliberately blunt blocklist for the suggestion bar. */
object SuggestionFilter {
    private val blocked = setOf(
        "fuck", "fucking", "fucked", "shit", "shitty", "bitch", "bastard", "asshole",
        "dick", "pussy", "cunt", "whore", "slut", "retard", "nigga", "nigger",
        "rape", "raped", "porn", "sex", "sexy", "nude", "nudes", "boobs", "cock",
        "chutiya", "chutya", "bhosdi", "bhosdike", "madarchod", "behenchod", "bhenchod",
        "gandu", "gaandu", "harami", "haramkhor", "randi", "saala", "sala", "kutta",
        "kamina", "kaminay", "lanat", "lund", "loda", "phudi", "chodo", "choda",
    )

    fun isBlocked(word: String): Boolean {
        val w = word.trim().lowercase().trim('.', ',', '!', '?', '\'', '"')
        return w in blocked
    }

    fun clean(words: List<String>): List<String> = words.filterNot { isBlocked(it) }
}

package com.example.keyboard

/**
 * Tiny prefix-based suggestion engine. It combines the words you use most (counts only)
 * with a small built-in list. A real dictionary and autocorrect come in a later version.
 */
class SuggestionEngine(private val builtIn: List<String> = COMMON_WORDS) {

    fun suggest(prefix: String, personal: Map<String, Int> = emptyMap(), limit: Int = 3): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return DEFAULTS.take(limit)
        val fromPersonal = personal.entries
            .filter { it.key.startsWith(p) && it.key != p }
            .sortedByDescending { it.value }
            .map { it.key }
        val fromBuiltIn = builtIn.filter { it.startsWith(p) && it != p }
        return (fromPersonal + fromBuiltIn).distinct().take(limit)
    }

    companion object {
        val DEFAULTS = listOf("I", "the", "and")

        val COMMON_WORDS = listOf(
            "the", "to", "of", "and", "in", "that", "have", "it", "for", "not", "on", "with", "he", "as",
            "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or",
            "will", "my", "one", "all", "would", "there", "their", "what", "so", "up", "out", "about",
            "who", "get", "which", "go", "me", "when", "make", "can", "like", "time", "just", "him", "know",
            "take", "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
            "than", "then", "now", "look", "only", "come", "its", "over", "think", "also", "back", "after",
            "use", "two", "how", "our", "work", "first", "well", "way", "even", "new", "want", "because",
            "any", "these", "give", "day", "most", "hello", "thanks", "thank", "please", "sorry", "okay",
            "today", "tomorrow", "tonight", "where", "why", "here", "going", "great", "tell", "call",
            "send", "message", "home", "love", "need", "help", "still", "right", "little", "much", "never",
            "always", "again", "really", "something", "nothing", "everything", "friend", "morning",
            "night", "meeting", "welcome", "yes", "hope", "wait", "sure", "should", "before", "later",
        )
    }
}

package com.example.keyboard

/**
 * Prefix-based suggestion engine. It combines the words you use most (counts only)
 * with the built-in frequency-ordered English dictionary. The dictionary lives in
 * assets/english_words.txt so it can be much larger than a hand-written list.
 */
class SuggestionEngine(private val builtIn: List<String> = COMMON_WORDS) {

    fun suggest(
        prefix: String,
        personal: Map<String, Int> = emptyMap(),
        limit: Int = 3,
        hidden: Set<String> = emptySet(),
        corrections: Map<String, String> = emptyMap(),
    ): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) {
            return DEFAULTS.filterNot { it.lowercase() in hidden }.take(limit)
        }
        // A mistake the user has already corrected gets offered first.
        val learned = corrections.entries
            .asSequence()
            .filter {
                (it.key == p || (p.length >= 3 && it.key.startsWith(p))) &&
                    it.value !in hidden && it.value != p
            }
            .map { it.value }
        val fromPersonal = personal.entries
            .asSequence()
            .filter { it.key.startsWith(p) && it.key != p && it.key !in hidden }
            .sortedByDescending { it.value }
            .map { it.key }
        val fromBuiltIn = builtIn.asSequence()
            .filter { it.startsWith(p) && it != p && it !in hidden }
        val prefixMatches = (learned + fromPersonal + fromBuiltIn).distinct().take(limit).toList()
        if (prefixMatches.isNotEmpty()) return prefixMatches

        // Nothing starts with this prefix: offer near-misses (one edit away) instead
        // of leaving the bar empty. This is the "gibberish" fallback.
        if (p.length < 3) return emptyList()
        val near = ArrayList<String>()
        for (entry in personal.entries.sortedByDescending { it.value }) {
            if (entry.key in hidden) continue
            if (withinOneEdit(p, entry.key)) {
                near.add(entry.key)
                if (near.size >= limit) return near.distinct()
            }
        }
        for (word in builtIn.asSequence().take(4000)) {
            if (word in hidden || word == p) continue
            if (withinOneEdit(p, word)) {
                near.add(word)
                if (near.size >= limit) break
            }
        }
        return near.distinct().take(limit)
    }

    /** True when [a] and [b] differ by at most one insert, delete, swap or substitute. */
    fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            edits++
            if (edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    // A transposition (teh -> the) counts as one edit.
                    if (i + 1 < a.length && j + 1 < b.length &&
                        a[i] == b[j + 1] && a[i + 1] == b[j]
                    ) {
                        i += 2
                        j += 2
                    } else {
                        i++
                        j++
                    }
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    companion object {
        val DEFAULTS = listOf("I", "the", "and")

        /** Fallback list if the asset cannot be read (also used by unit tests). */
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
            // Roman Urdu, so the English layout can suggest without switching languages.
            "kaise", "kaisa", "kaisi", "kya", "aap", "tum", "main", "hum", "nahi", "haan",
            "acha", "theek", "karo", "karna", "raha", "rahi", "gaya", "gayi", "bhai", "yaar",
            "abhi", "kal", "aaj", "phir", "bhi", "sirf", "bahut", "bohat", "kuch", "sab",
            "log", "ghar", "kaam", "paisa", "dost", "khana", "pani", "chai", "school", "office",
            "bhejo", "bhej", "dekho", "suno", "batao", "milte", "mile", "chalo", "ruk", "ruko",
            "shukriya", "meherbani", "salam", "assalam", "alaikum", "subah", "sham", "raat",
            "din", "hafta", "mahina", "saal", "kitna", "kitni", "kaun", "kahan", "kab", "kyun",
            "kiya", "kya", "hoga", "hogi", "hain", "tha", "thi", "the", "mera", "meri", "tumhara",
            "tumhari", "hamara", "hamari", "apna", "apni", "kuch", "koi", "aur", "lekin", "magar",
            "isliye", "kyunke", "agar", "toh", "phir", "ab", "yahan", "wahan", "andar", "bahar",
            "upar", "neeche", "jaldi", "dhire", "acha", "bura", "mushkil", "asan", "zaroor",
            "zaruri", "bilkul", "shayad", "pakka", "theek", "chalega", "chalta", "bata", "sach",
        )
    }
}

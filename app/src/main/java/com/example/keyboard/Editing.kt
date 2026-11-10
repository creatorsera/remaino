package com.example.keyboard

/** Small text-editing helpers kept separate so they are easy to unit test. */
object Editing {

    /**
     * Length of the word at the end of [text], plus any spaces or newlines after it.
     * Used as a fallback when the undo history was lost or the app changed the text.
     */
    fun trailingTokenLength(text: String): Int {
        if (text.isEmpty()) return 0
        var i = text.length
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return text.length - i
    }

    /** True for the characters that make up a word (letters, digits, apostrophes, hyphens). */
    fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '\'' || c == '\u2019' || c == '-'

    /**
     * How many characters must be removed on each side of the caret to delete the whole
     * word the caret sits in: `first` behind the caret, `second` ahead of it.
     * `("hello wor", "ld there")` -> `(3, 2)`.
     */
    fun wordDeleteCounts(before: String, after: String): Pair<Int, Int> {
        var b = before.length
        while (b > 0 && isWordChar(before[b - 1])) b--
        var a = 0
        while (a < after.length && isWordChar(after[a])) a++
        return (before.length - b) to a
    }

    /** Length of the first code point in [text]: 2 for a surrogate pair, 1 otherwise. */
    fun firstCodePointLength(text: String): Int {
        if (text.isEmpty()) return 0
        return if (text[0].isHighSurrogate() && text.length > 1 && text[1].isLowSurrogate()) 2 else 1
    }
}

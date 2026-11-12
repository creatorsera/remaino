package com.remino.keyboard

/**
 * Tight punctuation must not inherit the automatic space that was added after
 * the previous word ("how are you ?" -> "how are you?"). Math and separator
 * symbols keep their normal spacing.
 */
object Punctuation {
    private const val TIGHT = "?!.,;:"

    fun isTight(ch: Char): Boolean = ch in TIGHT

    /** True when [text] is a single character that should hug the previous word. */
    fun isTight(text: String): Boolean =
        text.length == 1 && isTight(text[0])
}

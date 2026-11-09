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
}

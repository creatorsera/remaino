package com.example.keyboard

enum class KeyType { CHAR, SHIFT, BACKSPACE, SYMBOLS, LETTERS, GLOBE, COMMA_EMOJI, SPACE, ENTER, SPACER }

data class Key(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val weight: Float = 1f,
    /** Draw with the darker "special key" color instead of the letter key color. */
    val tinted: Boolean = false,
)

/** Hard-coded English layouts. Later versions will load layouts from JSON files. */
object Layouts {
    private fun chars(s: String): List<Key> = s.map { Key(it.toString()) }

    private fun spacer(weight: Float) = Key("", KeyType.SPACER, weight)

    private fun bottomRow(first: Key): List<Key> = listOf(
        first,
        Key(",", KeyType.COMMA_EMOJI, 1.1f, tinted = true),
        Key("", KeyType.GLOBE, 1.1f),
        Key("English", KeyType.SPACE, 3.6f),
        Key(".", KeyType.CHAR, 1.1f, tinted = true),
        Key("", KeyType.ENTER, 1.6f),
    )

    fun rowCount(numberRow: Boolean): Int = if (numberRow) 5 else 4

    fun letters(numberRow: Boolean): List<List<Key>> {
        val main = listOf(
            chars("qwertyuiop"),
            listOf(spacer(0.5f)) + chars("asdfghjkl") + listOf(spacer(0.5f)),
            listOf(Key("", KeyType.SHIFT, 1.5f)) + chars("zxcvbnm") + listOf(Key("", KeyType.BACKSPACE, 1.5f)),
            bottomRow(Key("?123", KeyType.SYMBOLS, 1.5f)),
        )
        return if (numberRow) listOf(chars("1234567890")) + main else main
    }

    /** The symbols page always keeps the digits, and always has the same row count as the letters page. */
    fun symbols(numberRow: Boolean): List<List<Key>> {
        val digits = chars("1234567890")
        val punctuation = chars("@#\$%&-+()/")
        val bottom = bottomRow(Key("ABC", KeyType.LETTERS, 1.5f))
        return if (numberRow) {
            listOf(
                digits,
                punctuation,
                listOf(spacer(1f)) + chars("*\"':;!?_") + listOf(spacer(1f)),
                listOf(spacer(1.5f)) + chars("=[]{}<>") + listOf(Key("", KeyType.BACKSPACE, 1.5f)),
                bottom,
            )
        } else {
            listOf(
                digits,
                punctuation,
                listOf(spacer(1f)) + chars("*\"':;!?") + listOf(Key("", KeyType.BACKSPACE, 2f)),
                bottom,
            )
        }
    }
}

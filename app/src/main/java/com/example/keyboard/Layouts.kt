package com.example.keyboard

enum class KeyType { CHAR, SHIFT, BACKSPACE, SYMBOLS, LETTERS, GLOBE, SPACE, ENTER, SPACER }

data class Key(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val weight: Float = 1f,
)

/** Hard-coded English layouts. Later versions will load layouts from JSON files. */
object Layouts {
    private fun chars(s: String): List<Key> = s.map { Key(it.toString()) }

    private fun spacer(weight: Float) = Key("", KeyType.SPACER, weight)

    val letters: List<List<Key>> = listOf(
        chars("qwertyuiop"),
        listOf(spacer(0.5f)) + chars("asdfghjkl") + listOf(spacer(0.5f)),
        listOf(Key("", KeyType.SHIFT, 1.5f)) + chars("zxcvbnm") + listOf(Key("", KeyType.BACKSPACE, 1.5f)),
        listOf(
            Key("?123", KeyType.SYMBOLS, 1.3f),
            Key("", KeyType.GLOBE, 1.1f),
            Key("English", KeyType.SPACE, 4.9f),
            Key(".", KeyType.CHAR, 1.1f),
            Key("", KeyType.ENTER, 1.6f),
        ),
    )

    val symbols: List<List<Key>> = listOf(
        chars("1234567890"),
        chars("@#\$%&-+()/"),
        listOf(spacer(1.5f)) + chars("*\"':;!?") + listOf(Key("", KeyType.BACKSPACE, 1.5f)),
        listOf(
            Key("ABC", KeyType.LETTERS, 1.3f),
            Key("", KeyType.GLOBE, 1.1f),
            Key("English", KeyType.SPACE, 4.9f),
            Key(",", KeyType.CHAR, 1.1f),
            Key("", KeyType.ENTER, 1.6f),
        ),
    )
}

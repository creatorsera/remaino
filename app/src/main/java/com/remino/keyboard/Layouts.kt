package com.remino.keyboard

import android.content.Context

enum class KeyType {
    CHAR, SHIFT, BACKSPACE, SYMBOLS, LETTERS, GLOBE, COMMA, PERIOD, EMOJI,
    SPACE, ENTER, SPACER,
    /** Kept for older saved shortcuts and tests. */
    COMMA_EMOJI,
}

data class Key(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val weight: Float = 1f,
    /** Draw with the darker "special key" color instead of the letter key color. */
    val tinted: Boolean = false,
    /** The text this key commits. Defaults to its label. */
    val code: String = label,
    /** Characters offered by the long-press alternates row. */
    val longPress: List<String> = emptyList(),
)

/** Loads key maps from res/xml; no layout data lives in this file anymore. */
object Layouts {
    private var lettersCache: List<XmlLayoutLoader.Row>? = null
    private var symbolsCache: List<XmlLayoutLoader.Row>? = null

    private fun lettersRows(context: Context): List<XmlLayoutLoader.Row> =
        lettersCache ?: XmlLayoutLoader.load(context, R.xml.qwerty).also { lettersCache = it }

    private fun symbolsRows(context: Context): List<XmlLayoutLoader.Row> =
        symbolsCache ?: XmlLayoutLoader.load(context, R.xml.symbols).also { symbolsCache = it }

    fun rowCount(numberRow: Boolean): Int = if (numberRow) 5 else 4

    fun letters(context: Context, numberRow: Boolean): List<List<Key>> =
        lettersRows(context)
            .filter { !it.numberRowOnly || numberRow }
            .map { it.keys }

    fun symbols(context: Context, numberRow: Boolean): List<List<Key>> =
        symbolsRows(context)
            .filter { !it.numberRowOnly || numberRow }
            .map { it.keys }
}

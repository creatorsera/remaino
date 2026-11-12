package com.remino.keyboard

/** Bridge between the canvas key bed and the XML chrome above it. */
interface KeyboardChrome {
    fun setSuggestions(words: List<String>)
    fun setStatus(text: String?, clickable: Boolean = false)
    fun setListening(on: Boolean)
    fun setBarVisible(visible: Boolean)
    fun setLanguage(language: String)
    fun setMascotVisible(visible: Boolean)
    fun pokeMascot()
    fun applyTheme(theme: KeyboardTheme)
}

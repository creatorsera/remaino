package com.remino.keyboard

/** Colors and shapes of the keyboard. Dark (charcoal) is the default. */
data class KeyboardTheme(
    val base: Int,
    val toolbar: Int,
    val key: Int,
    val specialKey: Int,
    val text: Int,
    val textDim: Int,
    val icon: Int,
    val inverted: Int,
    val onInverted: Int,
    val toolbarRadiusDp: Float,
    val keyRadiusDp: Float,
    /** The single signal colour used for shift, the mic and the enter key. */
    val accent: Int = 0xFFC6F24E.toInt(),
    val onAccent: Int = 0xFF111114.toInt(),
    val card: Int = 0xFF232326.toInt(),
    /** Suggestion-bar surface, slightly tinted against the keys. */
    val bar: Int = 0xFF232326.toInt(),
    val divider: Int = 0xFF36363A.toInt(),
    val keyPressed: Int = 0xFF3A3A3D.toInt(),
    /** A crisp 1dp bottom edge on light keys; transparent in dark. */
    val keyEdge: Int = 0x00000000,
) {
    val isLight: Boolean get() = base == Light.base

    companion object {
        val Dark = KeyboardTheme(
            base = 0xFF1C1C1E.toInt(),
            toolbar = 0xFF1C1C1E.toInt(),
            key = 0xFF2C2C2E.toInt(),
            specialKey = 0xFF262628.toInt(),
            text = 0xFFF2F2F5.toInt(),
            textDim = 0xFF9A9AA1.toInt(),
            icon = 0xFFD9D9DE.toInt(),
            inverted = 0xFFF2F2F5.toInt(),
            onInverted = 0xFF1C1C1E.toInt(),
            toolbarRadiusDp = 14f,
            keyRadiusDp = 8f,
            accent = 0xFFC6F24E.toInt(),
            onAccent = 0xFF111114.toInt(),
            card = 0xFF232326.toInt(),
            bar = 0xFF232326.toInt(),
            divider = 0xFF36363A.toInt(),
            keyPressed = 0xFF3A3A3D.toInt(),
            keyEdge = 0x00000000,
        )

        /** Warm sand light theme with cream keys and a 1dp bottom edge. */
        val Light = KeyboardTheme(
            base = 0xFFE6E1D6.toInt(),
            toolbar = 0xFFE6E1D6.toInt(),
            key = 0xFFFAF7F0.toInt(),
            specialKey = 0xFFF1ECE1.toInt(),
            text = 0xFF1B1B1E.toInt(),
            textDim = 0xFF6B675E.toInt(),
            icon = 0xFF2E2E34.toInt(),
            inverted = 0xFF1B1B1E.toInt(),
            onInverted = 0xFFFAF7F0.toInt(),
            toolbarRadiusDp = 14f,
            keyRadiusDp = 8f,
            accent = 0xFFC6F24E.toInt(),
            onAccent = 0xFF1B1B1E.toInt(),
            card = 0xFFEFEAE0.toInt(),
            bar = 0xFFEFEAE0.toInt(),
            divider = 0xFFD9D2C5.toInt(),
            keyPressed = 0xFFE9E3D6.toInt(),
            keyEdge = 0xFFD8D0C2.toInt(),
        )
    }
}

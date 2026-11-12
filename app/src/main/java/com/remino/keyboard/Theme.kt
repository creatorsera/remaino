package com.remino.keyboard

/** Colors and shapes of the keyboard. Dark (shades of black) is the default. */
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
    /** The single signal colour used for the caret, the mascot and the enter key. */
    val accent: Int = 0xFFC8F26A.toInt(),
    val onAccent: Int = 0xFF0A0A0B.toInt(),
    val card: Int = 0xFF1C1C20.toInt(),
) {
    companion object {
        val Dark = KeyboardTheme(
            base = 0xFF121214.toInt(),
            toolbar = 0xFF1C1C20.toInt(),
            key = 0xFF2B2B31.toInt(),
            specialKey = 0xFF1F1F24.toInt(),
            text = 0xFFECECEF.toInt(),
            textDim = 0xFFA8A8B0.toInt(),
            icon = 0xFFD9D9DE.toInt(),
            inverted = 0xFFF2F2F5.toInt(),
            onInverted = 0xFF121214.toInt(),
            toolbarRadiusDp = 28f,
            keyRadiusDp = 10f,
            accent = 0xFFC8F26A.toInt(),
            onAccent = 0xFF0A0A0B.toInt(),
            card = 0xFF1C1C20.toInt(),
        )

        /** Warm paper light theme. Lime stays the signal colour, ink stays near-black. */
        val Light = KeyboardTheme(
            base = 0xFFEDEBE4.toInt(),
            toolbar = 0xFFFBFAF6.toInt(),
            key = 0xFFF7F6F1.toInt(),
            specialKey = 0xFFE4E2DA.toInt(),
            text = 0xFF121214.toInt(),
            textDim = 0xFF55555D.toInt(),
            icon = 0xFF2E2E34.toInt(),
            inverted = 0xFF121214.toInt(),
            onInverted = 0xFFF6F5F1.toInt(),
            toolbarRadiusDp = 28f,
            keyRadiusDp = 10f,
            accent = 0xFFC8F26A.toInt(),
            onAccent = 0xFF121214.toInt(),
            card = 0xFFFFFFFF.toInt(),
        )
    }
}

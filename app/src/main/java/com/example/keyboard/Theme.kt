package com.example.keyboard

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
            keyRadiusDp = 14f,
        )
    }
}

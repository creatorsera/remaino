package com.remino.keyboard

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptics and key sounds in one place so every key and toolbar button obeys
 * the two switches in Settings.
 */
object Feedback {
    fun tap(view: View, prefs: KeyboardPrefs) {
        if (prefs.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        click(view.context, prefs)
    }

    fun longPress(view: View, prefs: KeyboardPrefs) {
        if (prefs.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        click(view.context, prefs)
    }

    @Suppress("DEPRECATION")
    private fun click(context: Context, prefs: KeyboardPrefs) {
        if (!prefs.sound) return
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.7f)
        } catch (e: Exception) {
            // Sound is optional; never let it break typing.
        }
    }
}

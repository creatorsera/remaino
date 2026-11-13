package com.remino.keyboard

import android.view.LayoutInflater
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The XML chrome must inflate and bind without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardChromeTest {

    @Test
    fun chromeInflatesAndBinds() {
        val context = RuntimeEnvironment.getApplication()
        val root = LayoutInflater.from(context).inflate(R.layout.keyboard_input, null)

        val bar = root.findViewById<SuggestionBar>(R.id.suggestion_bar)
        val keyboard = root.findViewById<KeyboardView>(R.id.keyboard)
        assertNotNull(bar)
        assertNotNull(keyboard)

        keyboard.chrome = bar
        keyboard.setSuggestions(listOf("hello", "help", "helmet"))
        bar.setListening(true)
        bar.setStatus("Listening…", true)
        bar.applyTheme(KeyboardTheme.Light)
        bar.setDocked(true)
        bar.setMascotVisible(false)

        val tray = LayoutInflater.from(context).inflate(R.layout.options_tray, null) as OptionsTrayView
        tray.bind(KeyboardPrefs(context))
        tray.setTheme(KeyboardTheme.Light, MascotColor.MINT)

        assertTrue(bar.childCount > 0)
        assertTrue(tray.childCount > 0)
        assertTrue(bar.findViewById<android.view.View>(R.id.bar_icon).visibility == android.view.View.VISIBLE)
    }
}

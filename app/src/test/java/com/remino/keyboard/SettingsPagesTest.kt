package com.remino.keyboard

import android.content.Context
import android.os.Bundle
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every settings category must render without throwing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPagesTest {

    private val pages = listOf(
        "HOME", "APPEARANCE", "KEYBOARD", "REO", "TYPING", "CLIPBOARD",
        "PRIVACY", "SETUP", "ACTIVITY", "DATA", "UPDATES",
    )

    @Test
    fun everySettingsPageOpens() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)
            .edit().putLong("last_update_check", System.currentTimeMillis()).apply()
        for (name in pages) {
            val state = Bundle().apply { putString("settings_page", name) }
            val controller = Robolectric.buildActivity(SettingsActivity::class.java)
            controller.create(state).start().resume().visible()
            assertNotNull("page $name did not open", controller.get())
            controller.pause().stop().destroy()
        }
    }
}

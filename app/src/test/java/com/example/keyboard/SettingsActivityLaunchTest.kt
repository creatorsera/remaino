package com.example.keyboard

import android.content.Context
import android.os.Build
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Smoke test: the companion app must actually open without throwing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityLaunchTest {

    @Test
    fun settingsActivityOpens() {
        // Keep the automatic update check out of the unit test, so it never opens a socket.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)
            .edit().putLong("last_update_check", System.currentTimeMillis()).apply()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controller.setup()
        assertNotNull(controller.get())
    }
}

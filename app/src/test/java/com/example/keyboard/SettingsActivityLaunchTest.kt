package com.example.keyboard

import android.os.Build
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Smoke test: the companion app must actually open without throwing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityLaunchTest {

    @Test
    fun settingsActivityOpens() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controller.setup()
        assertNotNull(controller.get())
    }
}

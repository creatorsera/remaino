package com.example.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsAndBackupTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun dayKey(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    @Test
    fun streakCanBeLongerThanAYear() {
        val stats = TypingStats(context())
        stats.reset()
        val prefs = context().getSharedPreferences("typing_stats", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (i in 0 until 400) editor.putLong("d_" + dayKey(i), 5L)
        editor.apply()

        assertEquals(400, stats.streak())
    }

    @Test
    fun resetKeepsInstallDay() {
        val stats = TypingStats(context())
        stats.reset()
        stats.addWord()
        val installedBefore = stats.installDay()
        assertTrue(installedBefore.isNotEmpty())

        stats.reset()
        assertEquals(installedBefore, stats.installDay())
        assertEquals(0L, stats.totalWords())
        assertEquals(0L, stats.todayWords())
    }

    @Test
    fun backupRejectsDamagedFiles() {
        val failures = listOf(
            "not json",
            JSONObject().put("version", 2).put("settings", JSONObject()).toString(),
            JSONObject().put("version", 1).toString(),
        )
        for (text in failures) {
            val failed = try {
                BackupManager.parse(text)
                false
            } catch (e: BackupException) {
                true
            }
            assertTrue("Should reject: $text", failed)
        }
    }

    @Test
    fun backupAcceptsAValidFile() {
        val root = JSONObject()
            .put("version", 1)
            .put("settings", JSONObject().put("numberRow", true))
            .put("stats", JSONObject().put("total", 12L))
            .put("emojiRecents", JSONArray().put("🙂"))
        val parsed = BackupManager.parse(root.toString())
        assertEquals(1, parsed.getInt("version"))
    }

    @Test
    fun stickerSetHasVariety() {
        assertTrue(StickerMaker.palettes.size >= 10)
        assertTrue(StickerMaker.styles.size >= 5)
        assertEquals(StickerMaker.palettes.size, StickerMaker.paletteNames.size)
    }
}

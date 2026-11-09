package com.example.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun updateReleaseFindsApkAndChecksum() {
        val assets = JSONArray()
            .put(JSONObject().put("name", "remino-v0.6.0.apk")
                .put("size", 2_700_000L)
                .put("browser_download_url", "https://example.test/remino.apk"))
            .put(JSONObject().put("name", "remino-v0.6.0.apk.sha256")
                .put("browser_download_url", "https://example.test/remino.apk.sha256"))
        val release = UpdateChecker.parseRelease(
            JSONObject()
                .put("tag_name", "v0.6.0")
                .put("name", "Remino 0.6.0")
                .put("html_url", "https://example.test/release")
                .put("assets", assets),
        )
        assertTrue(release != null)
        assertEquals("0.6.0", release!!.tag)
        assertEquals("https://example.test/remino.apk", release.apkUrl)
        assertEquals("https://example.test/remino.apk.sha256", release.checksumUrl)
        assertEquals(2_700_000L, release.apkSize)
    }

    @Test
    fun releaseWithoutApkIsStillUsableAsAPageLink() {
        val release = UpdateChecker.parseRelease(
            JSONObject().put("tag_name", "v1.0.0").put("html_url", "https://example.test/r"),
        )
        assertTrue(release != null)
        assertNull(release!!.apkUrl)
        assertNull(release.checksumUrl)
    }

    @Test
    fun versionComparisonHandlesDifferentLengths() {
        assertTrue(UpdateChecker.isNewer("0.6.0", "0.5.0"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
        assertTrue(!UpdateChecker.isNewer("0.5.0", "0.5.0"))
        assertTrue(!UpdateChecker.isNewer("0.4.9", "0.5.0"))
    }

    @Test
    fun stickerSetHasVariety() {
        assertTrue(StickerMaker.palettes.size >= 10)
        assertTrue(StickerMaker.styles.size >= 5)
        assertEquals(StickerMaker.palettes.size, StickerMaker.paletteNames.size)
    }
    @Test
    fun englishDictionaryHasLongCommonWords() {
        val words = EnglishWords.load(RuntimeEnvironment.getApplication())
        assertTrue(words.size >= 5_000)
        assertTrue(words.contains("keyboard"))
        assertTrue(words.contains("because"))
        assertTrue(words.contains("information"))
    }

    @Test
    fun hiddenSuggestionSurvivesANewInstance() {
        val context = RuntimeEnvironment.getApplication()
        val hidden = HiddenWords(context)
        hidden.clear()
        assertTrue(hidden.hide("hello"))
        assertFalse(HiddenWords(context).hide("hello"))
        assertTrue(HiddenWords(context).contains("hello"))
        assertEquals(1, HiddenWords(context).count())
        hidden.clear()
        assertEquals(0, HiddenWords(context).count())
    }

}

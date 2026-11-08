package com.example.keyboard

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only part of the app that uses the internet: a release check against GitHub.
 * It runs at most once a week, or when the user taps "Check for updates".
 * Nothing is sent except the request itself; there is no identifier or analytics.
 */
object UpdateChecker {
    private const val ENDPOINT =
        "https://api.github.com/repos/creatorsera/remaino/releases/latest"

    data class Release(val tag: String, val name: String, val pageUrl: String)

    fun fetchLatest(): Release? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Remaino-Android")
            }
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").trim()
            if (tag.isEmpty()) return null
            Release(
                tag = tag.removePrefix("v"),
                name = json.optString("name", tag),
                pageUrl = json.optString("html_url", "https://github.com/creatorsera/remaino/releases"),
            )
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** True when [remote] looks newer than [installed]. Both are dotted version names. */
    fun isNewer(remote: String, installed: String): Boolean {
        val a = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val b = installed.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

package com.example.keyboard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateException(message: String) : Exception(message)

/** 404 means the repository exists but has not published a release yet. */
class NoReleaseException : Exception()

/**
 * The only part of the app that uses the internet: a release check against GitHub.
 * It runs at most once a week, or when the user taps "Check for updates".
 *
 * The check reads the newest GitHub release, then the update screen can download its
 * APK and the matching `.sha256` asset. Nothing is installed until the checksum
 * matches, the package name matches, the version code is newer and the signing
 * certificate matches the installed app. Nothing is sent except the request itself.
 */
object UpdateChecker {
    private const val REPO = "creatorsera/remaino"
    private const val ENDPOINT = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val MAX_APK_BYTES = 150L * 1024L * 1024L
    private const val MAX_CHECKSUM_BYTES = 4L * 1024L

    data class Release(
        val tag: String,
        val name: String,
        val pageUrl: String,
        val apkUrl: String?,
        val apkSize: Long,
        val checksumUrl: String?,
        val notes: String,
    )

    data class ApkInfo(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
    )

    sealed class Check {
        data class Found(val release: Release) : Check()
        data class NoRelease(val message: String) : Check()
        data class Failed(val message: String) : Check()
    }

    fun fetchLatest(): Release? = when (val result = checkLatest()) {
        is Check.Found -> result.release
        else -> null
    }

    /** Explains *why* a check failed instead of always saying GitHub is unreachable. */
    fun checkLatest(): Check {
        return try {
            val body = request(ENDPOINT, MAX_APK_BYTES)
            val release = parseRelease(JSONObject(body))
                ?: return Check.NoRelease("GitHub has no release for this repository yet.")
            Check.Found(release)
        } catch (e: NoReleaseException) {
            Check.NoRelease("No Remino release has been published yet.")
        } catch (e: Exception) {
            Check.Failed(e.message ?: "Network error")
        }
    }

    /** Separated from the network call so release parsing can be tested. */
    fun parseRelease(json: JSONObject): Release? {
        val tag = json.optString("tag_name", "").trim().removePrefix("v")
        if (tag.isEmpty()) return null
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        var apkSize = 0L
        var checksumUrl: String? = null
        var apkName = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && apkUrl == null) {
                    apkUrl = url.ifEmpty { null }
                    apkSize = asset.optLong("size", 0L)
                    apkName = name
                }
            }
            if (apkName.isNotEmpty()) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name == "$apkName.sha256") {
                        checksumUrl = asset.optString("browser_download_url", "").ifEmpty { null }
                        break
                    }
                }
            }
        }
        return Release(
            tag = tag,
            name = json.optString("name", tag).ifEmpty { tag },
            pageUrl = json.optString("html_url", "$RELEASES_PAGE/tag/v$tag"),
            apkUrl = apkUrl,
            apkSize = apkSize,
            checksumUrl = checksumUrl,
            notes = json.optString("body", "").take(20_000),
        )
    }

    /**
     * Downloads the release APK to [target] and checks it against the release checksum.
     * [onProgress] gets 0..100, or -1 while the size is still unknown.
     */
    fun download(release: Release, target: File, onProgress: (Int) -> Unit): File {
        val apkUrl = release.apkUrl ?: throw UpdateException("This release has no APK attached.")
        val checksumUrl = release.checksumUrl
            ?: throw UpdateException("This release has no checksum, so it cannot be verified.")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        try {
            downloadTo(apkUrl, temp, release.apkSize, onProgress)
            if (temp.length() <= 0L) throw UpdateException("The downloaded file is empty.")
            val expected = request(checksumUrl, MAX_CHECKSUM_BYTES)
                .trim()
                .substringBefore(' ')
                .lowercase()
            if (expected.length != 64 || expected.any { it !in "0123456789abcdef" }) {
                throw UpdateException("The release checksum is not valid.")
            }
            if (sha256(temp) != expected) {
                throw UpdateException("The download did not match the release checksum.")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        } catch (e: Exception) {
            temp.delete()
            if (e is UpdateException) throw e
            throw UpdateException("Could not download the update.")
        }
    }

    private fun downloadTo(url: String, target: File, expectedSize: Long, onProgress: (Int) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            connection = open(url).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            if (connection.responseCode !in 200..299) {
                throw UpdateException("GitHub returned ${connection.responseCode}.")
            }
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: expectedSize
            if (total > MAX_APK_BYTES) throw UpdateException("The update file is too large.")
            var written = 0L
            FileOutputStream(target).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > MAX_APK_BYTES) throw UpdateException("The update file is too large.")
                        out.write(buffer, 0, read)
                        onProgress(if (total > 0L) ((written * 100) / total).toInt().coerceIn(0, 100) else -1)
                    }
                }
            }
            if (written == 0L) throw UpdateException("The update file is empty.")
        } finally {
            connection?.disconnect()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Reads package name, version and signing certificate from a downloaded APK. */
    fun inspectApk(context: Context, file: File): ApkInfo? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signFlags()) ?: return null
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return ApkInfo(info.packageName, code, info.versionName ?: "")
    }

    /**
     * True when the downloaded APK is signed with the same key as the installed app.
     * Android refuses to replace an app with a different key, so checking first lets
     * the UI explain the problem instead of failing at the installer.
     */
    fun sameSigner(context: Context, file: File): Boolean {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, signFlags()) ?: return false
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, signFlags())
        } catch (e: Exception) {
            return false
        }
        val a = signerDigest(archive)
        val b = signerDigest(installed)
        return a != null && b != null && a == b
    }

    private fun signFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun signerDigest(info: android.content.pm.PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return null
        val first = signatures.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun request(url: String, limit: Long): String {
        var connection: HttpURLConnection? = null
        try {
            connection = open(url)
            val code = connection.responseCode
            if (code == 404) throw NoReleaseException()
            if (code !in 200..299) {
                val reason = if (code == 403) "GitHub rate limit reached" else "GitHub returned $code"
                throw UpdateException(reason)
            }
            val builder = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(16 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                    if (builder.length > limit) throw UpdateException("The response is too large.")
                }
            }
            return builder.toString()
        } finally {
            connection?.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Remino-Android")
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

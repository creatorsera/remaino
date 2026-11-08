package com.example.keyboard

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * Password-protected backup file: AES-256-GCM, key derived from the password with PBKDF2-HMAC-SHA256.
 * File layout: "RMBK1" | iterations (4 bytes) | salt (16) | iv (12) | encrypted data.
 * A forgotten password cannot be recovered.
 */
object BackupCrypto {
    private val MAGIC = "RMBK1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    const val DEFAULT_ITERATIONS = 600_000

    fun encrypt(plain: String, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN)
        val iv = ByteArray(IV_LEN)
        random.nextBytes(salt)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iterBytes = ByteBuffer.allocate(4).putInt(iterations).array()
        return MAGIC + iterBytes + salt + iv + encrypted
    }

    fun decrypt(data: ByteArray, password: CharArray): String {
        val headerLen = MAGIC.size + 4 + SALT_LEN + IV_LEN
        if (data.size <= headerLen || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("This is not a Remino backup file.")
        }
        val iterations = ByteBuffer.wrap(data, MAGIC.size, 4).int
        if (iterations < 10_000 || iterations > 5_000_000) throw BackupException("The backup file is damaged.")
        val saltStart = MAGIC.size + 4
        val salt = data.copyOfRange(saltStart, saltStart + SALT_LEN)
        val iv = data.copyOfRange(saltStart + SALT_LEN, headerLen)
        val encrypted = data.copyOfRange(headerLen, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        try {
            return String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: BadPaddingException) {
            throw BackupException("Wrong password, or the file is damaged.")
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

/** Collects everything Remino keeps on the device into one JSON text, and restores it. */
object BackupManager {
    fun buildJson(context: Context, includeClipboard: Boolean = false): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("settings", KeyboardPrefs(context).exportData())
        root.put("stats", TypingStats(context).exportData())
        root.put("words", WordFrequencies.get(context).exportData())
        root.put("emojiRecents", EmojiRecents(context).exportData())
        if (includeClipboard) root.put("clipboard", ClipboardStore.get(context).exportData())
        return root.toString()
    }

    fun apply(context: Context, json: String) {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw BackupException("The backup content is damaged.")
        }
        if (root.optInt("version", 0) != 1) throw BackupException("This backup is from a different version.")
        root.optJSONObject("settings")?.let { KeyboardPrefs(context).importData(it) }
        root.optJSONObject("stats")?.let { TypingStats(context).importData(it) }
        root.optJSONObject("words")?.let { WordFrequencies.get(context).importData(it) }
        root.optJSONArray("emojiRecents")?.let { EmojiRecents(context).importData(it) }
        root.optJSONArray("clipboard")?.let { ClipboardStore.get(context).importData(it) }
    }
}

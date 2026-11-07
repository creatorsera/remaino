package com.example.keyboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ClipItem(val text: String, val pinned: Boolean, val time: Long)

/** Pure list rules for the clipboard history (newest first). */
object ClipboardLogic {
    const val MAX_UNPINNED = 50

    fun add(items: List<ClipItem>, text: String, now: Long): List<ClipItem> {
        val existing = items.firstOrNull { it.text == text }
        val rest = items.filter { it.text != text }
        return trim(listOf(ClipItem(text, existing?.pinned ?: false, now)) + rest)
    }

    /** Keeps every pinned item and the newest MAX_UNPINNED others. */
    fun trim(items: List<ClipItem>): List<ClipItem> {
        var unpinned = 0
        return items.filter {
            if (it.pinned) {
                true
            } else {
                unpinned++
                unpinned <= MAX_UNPINNED
            }
        }
    }

    /** Pinned items never expire. expireHours of 0 means never. */
    fun prune(items: List<ClipItem>, now: Long, expireHours: Int): List<ClipItem> =
        if (expireHours <= 0) items else items.filter { it.pinned || now - it.time < expireHours * 3_600_000L }

    fun togglePin(items: List<ClipItem>, text: String): List<ClipItem> =
        items.map { if (it.text == text) it.copy(pinned = !it.pinned) else it }

    fun remove(items: List<ClipItem>, text: String): List<ClipItem> = items.filter { it.text != text }

    fun clearUnpinned(items: List<ClipItem>): List<ClipItem> = items.filter { it.pinned }
}

/** Encrypts with a key that lives inside the Android Keystore and never leaves it. */
object KeystoreBox {
    private const val ALIAS = "remaino_clipboard_key"
    private const val IV_LEN = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data, IV_LEN, data.size - IV_LEN)
    }
}

/**
 * Clipboard history kept in one encrypted file in the app's private storage.
 * Shared by the keyboard and the settings screen.
 */
class ClipboardStore private constructor(context: Context) {
    private val file = File(context.filesDir, "clipboard.bin")
    private val options = KeyboardPrefs(context)
    private var items: List<ClipItem> = emptyList()
    private var loaded = false

    var paused: Boolean
        get() = options.clipboardPaused
        set(value) {
            options.clipboardPaused = value
        }

    @Synchronized
    fun items(): List<ClipItem> {
        ensureLoaded()
        val pruned = ClipboardLogic.prune(items, System.currentTimeMillis(), options.clipboardExpireHours)
        if (pruned.size != items.size) {
            items = pruned
            save()
        }
        return items
    }

    @Synchronized
    fun add(text: String) {
        if (!options.clipboardEnabled || options.clipboardPaused) return
        ensureLoaded()
        items = ClipboardLogic.add(items, text, System.currentTimeMillis())
        save()
    }

    @Synchronized
    fun togglePin(text: String) {
        ensureLoaded()
        items = ClipboardLogic.togglePin(items, text)
        save()
    }

    @Synchronized
    fun remove(text: String) {
        ensureLoaded()
        items = ClipboardLogic.remove(items, text)
        save()
    }

    @Synchronized
    fun clearUnpinned() {
        ensureLoaded()
        items = ClipboardLogic.clearUnpinned(items)
        save()
    }

    @Synchronized
    fun clearAll() {
        items = emptyList()
        loaded = true
        file.delete()
    }

    @Synchronized
    fun exportData(): JSONArray {
        ensureLoaded()
        return toJson(items)
    }

    @Synchronized
    fun importData(arr: JSONArray) {
        items = ClipboardLogic.trim(parse(arr))
        loaded = true
        save()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        items = try {
            if (file.exists()) {
                parse(JSONArray(String(KeystoreBox.decrypt(file.readBytes()), Charsets.UTF_8)))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save() {
        try {
            file.writeBytes(KeystoreBox.encrypt(toJson(items).toString().toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            // Keep working in memory if storage is unavailable.
        }
    }

    private fun parse(arr: JSONArray): List<ClipItem> {
        val out = ArrayList<ClipItem>()
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            val text = o.optString("t", "")
            if (text.isNotEmpty()) out.add(ClipItem(text, o.optBoolean("p", false), o.optLong("c", 0L)))
        }
        return out
    }

    private fun toJson(list: List<ClipItem>): JSONArray {
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().put("t", item.text).put("p", item.pinned).put("c", item.time))
        }
        return arr
    }

    companion object {
        @Volatile
        private var instance: ClipboardStore? = null

        fun get(context: Context): ClipboardStore =
            instance ?: synchronized(this) {
                instance ?: ClipboardStore(context.applicationContext).also { instance = it }
            }
    }
}

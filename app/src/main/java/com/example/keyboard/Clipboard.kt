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

data class ClipItem(
    val text: String,
    val pinned: Boolean,
    val time: Long,
    /** Base64 PNG/JPEG bytes for an image item; null for text. */
    val image: String? = null,
)

/** Pure list rules for the clipboard history (newest first). */
object ClipboardLogic {
    const val MAX_UNPINNED = 50

    fun add(items: List<ClipItem>, text: String, now: Long): List<ClipItem> {
        val existing = items.firstOrNull { it.text == text && it.image == null }
        val rest = items.filterNot { it.text == text && it.image == null }
        return trim(listOf(ClipItem(text, existing?.pinned ?: false, now)) + rest)
    }

    /** Adds an image item. Identity is its base64 payload, so the same image is not duplicated. */
    fun addImage(items: List<ClipItem>, image: String, now: Long): List<ClipItem> {
        val existing = items.firstOrNull { it.image == image }
        val rest = items.filter { it.image != image }
        return trim(listOf(ClipItem("Image", existing?.pinned ?: false, now, image)) + rest)
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

    fun remove(items: List<ClipItem>, text: String): List<ClipItem> =
        items.filterNot { it.text == text }

    /** Removes one exact item (text or image). */
    fun removeItem(items: List<ClipItem>, item: ClipItem): List<ClipItem> =
        items.filterNot { it === item || (it.text == item.text && it.image == item.image) }

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

    /** Stores an image and returns the row that was created, or null if history is off. */
    @Synchronized
    fun addImage(base64: String): ClipItem? {
        if (!options.clipboardEnabled || options.clipboardPaused || !options.clipboardImages) return null
        ensureLoaded()
        items = ClipboardLogic.addImage(items, base64, System.currentTimeMillis())
        save()
        return items.firstOrNull { it.image == base64 }
    }

    @Synchronized
    fun togglePin(text: String) {
        ensureLoaded()
        items = ClipboardLogic.togglePin(items, text)
        save()
    }

    @Synchronized
    fun togglePin(item: ClipItem) {
        ensureLoaded()
        val idx = items.indexOfFirst { it.text == item.text && it.image == item.image }
        if (idx >= 0) {
            items = items.toMutableList().also {
                it[idx] = it[idx].copy(pinned = !it[idx].pinned)
            }
            save()
        }
    }

    @Synchronized
    fun remove(item: ClipItem) {
        ensureLoaded()
        items = ClipboardLogic.removeItem(items, item)
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
        val count = minOf(arr.length(), MAX_ITEMS)
        for (i in 0 until count) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            val text = o.optString("t", "").take(MAX_TEXT)
            val image = if (o.has("i")) o.optString("i", "") else ""
            val pinned = o.optBoolean("p", false)
            val time = o.optLong("c", 0L).coerceAtLeast(0L)
            if (image.isNotEmpty() && image.length <= MAX_IMAGE_CHARS) {
                out.add(ClipItem(text.ifEmpty { "Image" }, pinned, time, image))
            } else if (text.isNotEmpty()) {
                out.add(ClipItem(text, pinned, time))
            }
        }
        return out
    }

    private fun toJson(list: List<ClipItem>): JSONArray {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().put("t", item.text).put("p", item.pinned).put("c", item.time)
            if (item.image != null) obj.put("i", item.image)
            arr.put(obj)
        }
        return arr
    }

    companion object {
        private const val MAX_ITEMS = 300
        private const val MAX_TEXT = 10_000
        private const val MAX_IMAGE_CHARS = 15_000_000

        @Volatile
        private var instance: ClipboardStore? = null

        fun get(context: Context): ClipboardStore =
            instance ?: synchronized(this) {
                instance ?: ClipboardStore(context.applicationContext).also { instance = it }
            }
    }
}

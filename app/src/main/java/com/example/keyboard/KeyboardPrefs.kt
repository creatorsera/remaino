package com.example.keyboard

import android.content.Context
import org.json.JSONObject

/** Keyboard options chosen in the settings screen. */
class KeyboardPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)

    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply()
        }

    var keyPreview: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PREVIEW, value).apply()
        }

    /** The little mascot that pushes the cursor back while you delete. */
    var mascot: Boolean
        get() = prefs.getBoolean(KEY_MASCOT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MASCOT, value).apply()
        }

    var clipboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIP_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CLIP_ENABLED, value).apply()
        }

    /** Paused from the clipboard panel. Not part of backups. */
    var clipboardPaused: Boolean
        get() = prefs.getBoolean(KEY_CLIP_PAUSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLIP_PAUSED, value).apply()
        }

    /** Hours before unpinned clipboard items are deleted. 0 means never. */
    var clipboardExpireHours: Int
        get() = prefs.getInt(KEY_CLIP_EXPIRE, 24)
        set(value) {
            prefs.edit().putInt(KEY_CLIP_EXPIRE, value).apply()
        }

    fun exportData(): JSONObject = JSONObject()
        .put("numberRow", showNumberRow)
        .put("keyPreview", keyPreview)
        .put("mascot", mascot)
        .put("clipboardEnabled", clipboardEnabled)
        .put("clipboardExpireHours", clipboardExpireHours)

    fun importData(json: JSONObject) {
        if (json.has("numberRow")) showNumberRow = json.getBoolean("numberRow")
        if (json.has("keyPreview")) keyPreview = json.getBoolean("keyPreview")
        if (json.has("mascot")) mascot = json.getBoolean("mascot")
        if (json.has("clipboardEnabled")) clipboardEnabled = json.getBoolean("clipboardEnabled")
        if (json.has("clipboardExpireHours")) clipboardExpireHours = json.getInt("clipboardExpireHours")
    }

    private companion object {
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_PREVIEW = "key_preview"
        const val KEY_MASCOT = "mascot"
        const val KEY_CLIP_ENABLED = "clip_enabled"
        const val KEY_CLIP_PAUSED = "clip_paused"
        const val KEY_CLIP_EXPIRE = "clip_expire_hours"
    }
}

package com.example.keyboard

import android.content.Context
import org.json.JSONObject

/** Keyboard options chosen in the settings screen. */
class KeyboardPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)

    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply()
        }

    var keyPreview: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PREVIEW, value).apply()
        }

    fun exportData(): JSONObject =
        JSONObject().put("numberRow", showNumberRow).put("keyPreview", keyPreview)

    fun importData(json: JSONObject) {
        if (json.has("numberRow")) showNumberRow = json.getBoolean("numberRow")
        if (json.has("keyPreview")) keyPreview = json.getBoolean("keyPreview")
    }

    private companion object {
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_PREVIEW = "key_preview"
    }
}

package com.remino.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Where the mascot is drawn. CURSOR needs the "display over other apps" permission. */
enum class MascotPlacement(val key: String) {
    TOOLBAR("toolbar"),
    ABOVE("above"),
    CURSOR("cursor");

    companion object {
        fun from(value: String?): MascotPlacement =
            entries.firstOrNull { it.key == value } ?: CURSOR
    }
}

/** Light, dark, or following the system setting. */
enum class Appearance(val key: String, val label: String) {
    SYSTEM("system", "Auto"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    /** The theme to draw, given whether the system is currently in dark mode. */
    fun theme(systemDark: Boolean): KeyboardTheme = when (this) {
        SYSTEM -> if (systemDark) KeyboardTheme.Dark else KeyboardTheme.Light
        LIGHT -> KeyboardTheme.Light
        DARK -> KeyboardTheme.Dark
    }

    companion object {
        fun from(value: String?): Appearance =
            entries.firstOrNull { it.key == value } ?: SYSTEM
    }
}

/** Reo's coat colours. The face always uses a dark ink so every coat stays readable. */
enum class MascotColor(val key: String, val label: String, val body: Int, val ink: Int) {
    LIME("lime", "Lime", 0xFFC8F26A.toInt(), 0xFF0A0A0B.toInt()),
    MINT("mint", "Mint", 0xFF8EF0C0.toInt(), 0xFF0A0A0B.toInt()),
    SKY("sky", "Sky", 0xFF8FD3FF.toInt(), 0xFF0A0A0B.toInt()),
    VIOLET("violet", "Violet", 0xFFB9A0FF.toInt(), 0xFF0A0A0B.toInt()),
    CORAL("coral", "Coral", 0xFFFF9E9E.toInt(), 0xFF0A0A0B.toInt()),
    AMBER("amber", "Amber", 0xFFFFD166.toInt(), 0xFF0A0A0B.toInt()),
    ROSE("rose", "Rose", 0xFFFFA8D5.toInt(), 0xFF0A0A0B.toInt()),
    SNOW("snow", "Snow", 0xFFF2F2F5.toInt(), 0xFF0A0A0B.toInt());

    companion object {
        fun from(value: String?): MascotColor =
            entries.firstOrNull { it.key == value } ?: LIME
    }
}

/**
 * Keyboard options chosen in the settings screen.
 *
 * Settings are versioned. migrate() runs once per install/upgrade and fills in the
 * new keys without touching anything the user already chose.
 */
class KeyboardPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    // ---- keyboard ----
    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, false)
        set(value) { prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply() }

    var keyPreview: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW, false)
        set(value) { prefs.edit().putBoolean(KEY_PREVIEW, value).apply() }

    /** Light, dark, or follow the phone. */
    var appearance: Appearance
        get() = Appearance.from(prefs.getString(KEY_APPEARANCE, null))
        set(value) { prefs.edit().putString(KEY_APPEARANCE, value.key).apply() }

    /** The little mascot that keeps the caret company. */
    var mascot: Boolean
        get() = prefs.getBoolean(KEY_MASCOT, true)
        set(value) { prefs.edit().putBoolean(KEY_MASCOT, value).apply() }

    var mascotPlacement: MascotPlacement
        get() = MascotPlacement.from(prefs.getString(KEY_MASCOT_PLACEMENT, null))
        set(value) { prefs.edit().putString(KEY_MASCOT_PLACEMENT, value.key).apply() }

    /** Reo's coat colour. */
    var mascotColor: MascotColor
        get() = MascotColor.from(prefs.getString(KEY_MASCOT_COLOR, null))
        set(value) { prefs.edit().putString(KEY_MASCOT_COLOR, value.key).apply() }

    /** Reo's size at the cursor, as a percentage (70..140). Only the overlay uses it. */
    var mascotScale: Int
        get() = prefs.getInt(KEY_MASCOT_SCALE, 100)
        set(value) { prefs.edit().putInt(KEY_MASCOT_SCALE, value.coerceIn(70, 140)).apply() }

    /** Draw the rounded suggestion card and its options curtain instead of the flat strip. */
    var suggestionDock: Boolean
        get() = prefs.getBoolean(KEY_SUGGESTION_DOCK, true)
        set(value) { prefs.edit().putBoolean(KEY_SUGGESTION_DOCK, value).apply() }

    /** Fine nudge, in dp. Negative is up. */
    var mascotOffsetY: Int
        get() = prefs.getInt(KEY_MASCOT_OFFSET_Y, -3)
        set(value) { prefs.edit().putInt(KEY_MASCOT_OFFSET_Y, value.coerceIn(-24, 24)).apply() }

    /** Negative is left. */
    var mascotOffsetX: Int
        get() = prefs.getInt(KEY_MASCOT_OFFSET_X, 0)
        set(value) { prefs.edit().putInt(KEY_MASCOT_OFFSET_X, value.coerceIn(-24, 24)).apply() }

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) { prefs.edit().putBoolean(KEY_HAPTICS, value).apply() }

    var sound: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(value) { prefs.edit().putBoolean(KEY_SOUND, value).apply() }

    /** Capitalise the start of a sentence automatically. */
    var autoCapitalize: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAP, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_CAP, value).apply() }

    /** Capitalise the first letter when a new field is opened. */
    var capitalizeFirstLetter: Boolean
        get() = prefs.getBoolean(KEY_CAP_FIRST, true)
        set(value) { prefs.edit().putBoolean(KEY_CAP_FIRST, value).apply() }

    var nextWordSuggestions: Boolean
        get() = prefs.getBoolean(KEY_NEXT_WORD, true)
        set(value) { prefs.edit().putBoolean(KEY_NEXT_WORD, value).apply() }

    var emojiPrediction: Boolean
        get() = prefs.getBoolean(KEY_EMOJI_PREDICTION, true)
        set(value) { prefs.edit().putBoolean(KEY_EMOJI_PREDICTION, value).apply() }

    var blockInappropriate: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_INAPPROPRIATE, true)
        set(value) { prefs.edit().putBoolean(KEY_BLOCK_INAPPROPRIATE, value).apply() }

    /** 0 = no skin tone, 1..5 = U+1F3FB..U+1F3FF. */
    var emojiTone: Int
        get() = prefs.getInt(KEY_EMOJI_TONE, 0)
        set(value) { prefs.edit().putInt(KEY_EMOJI_TONE, value.coerceIn(0, 5)).apply() }

    /** Keyboard height as a percentage of the default (80..130). */
    var keyboardHeightPercent: Int
        get() = prefs.getInt(KEY_HEIGHT_PERCENT, 100)
        set(value) { prefs.edit().putInt(KEY_HEIGHT_PERCENT, value.coerceIn(80, 130)).apply() }

    /** Extra space under the keys, in dp (0..24). */
    var keyboardPadding: Int
        get() = prefs.getInt(KEY_BOTTOM_PADDING, 0)
        set(value) { prefs.edit().putInt(KEY_BOTTOM_PADDING, value.coerceIn(0, 24)).apply() }

    /** Draw the keyboard in a movable overlay window. Needs "display over other apps". */
    var floatingKeyboard: Boolean
        get() = prefs.getBoolean(KEY_FLOATING, false)
        set(value) { prefs.edit().putBoolean(KEY_FLOATING, value).apply() }

    /** The suggestion bar can float above the keys or sit docked in the panel. */
    var floatingBar: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_BAR, false)
        set(value) { prefs.edit().putBoolean(KEY_FLOATING_BAR, value).apply() }

    /** Last position of the floating keyboard in pixels, or -1 when it was never moved. */
    var floatingX: Int
        get() = prefs.getInt(KEY_FLOATING_X, -1)
        set(value) { prefs.edit().putInt(KEY_FLOATING_X, value).apply() }

    var floatingY: Int
        get() = prefs.getInt(KEY_FLOATING_Y, -1)
        set(value) { prefs.edit().putInt(KEY_FLOATING_Y, value).apply() }

    // ---- clipboard ----
    var clipboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIP_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_CLIP_ENABLED, value).apply() }

    /** Paused from the clipboard panel. Not part of backups. */
    var clipboardPaused: Boolean
        get() = prefs.getBoolean(KEY_CLIP_PAUSED, false)
        set(value) { prefs.edit().putBoolean(KEY_CLIP_PAUSED, value).apply() }

    /** Hours before unpinned clipboard items are deleted. 0 means never. */
    var clipboardExpireHours: Int
        get() = prefs.getInt(KEY_CLIP_EXPIRE, 24)
        set(value) { prefs.edit().putInt(KEY_CLIP_EXPIRE, value.coerceIn(0, 8760)).apply() }

    var clipboardImages: Boolean
        get() = prefs.getBoolean(KEY_CLIP_IMAGES, true)
        set(value) { prefs.edit().putBoolean(KEY_CLIP_IMAGES, value).apply() }

    // ---- privacy ----
    var perAppSensitive: Boolean
        get() = prefs.getBoolean(KEY_PER_APP_SENSITIVE, true)
        set(value) { prefs.edit().putBoolean(KEY_PER_APP_SENSITIVE, value).apply() }

    fun sensitivePackages(): Set<String> {
        val raw = prefs.getString(KEY_SENSITIVE_PACKAGES, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun setSensitivePackages(packages: Set<String>) {
        val arr = JSONArray()
        packages.filter { it.isNotBlank() }.forEach { arr.put(it) }
        prefs.edit().putString(KEY_SENSITIVE_PACKAGES, arr.toString()).apply()
    }

    fun isSensitivePackage(packageName: String?): Boolean =
        perAppSensitive && packageName != null && packageName in sensitivePackages()

    // ---- backup ----

    fun exportData(): JSONObject = JSONObject()
        .put("numberRow", showNumberRow)
        .put("keyPreview", keyPreview)
        .put("appearance", appearance.key)
        .put("mascot", mascot)
        .put("mascotPlacement", mascotPlacement.key)
        .put("mascotColor", mascotColor.key)
        .put("mascotOffsetX", mascotOffsetX)
        .put("mascotOffsetY", mascotOffsetY)
        .put("mascotScale", mascotScale)
        .put("suggestionDock", suggestionDock)
        .put("haptics", haptics)
        .put("sound", sound)
        .put("autoCapitalize", autoCapitalize)
        .put("capitalizeFirstLetter", capitalizeFirstLetter)
        .put("nextWordSuggestions", nextWordSuggestions)
        .put("emojiPrediction", emojiPrediction)
        .put("blockInappropriate", blockInappropriate)
        .put("emojiTone", emojiTone)
        .put("keyboardHeightPercent", keyboardHeightPercent)
        .put("keyboardPadding", keyboardPadding)
        .put("floatingKeyboard", floatingKeyboard)
        .put("floatingBar", floatingBar)
        .put("clipboardEnabled", clipboardEnabled)
        .put("clipboardExpireHours", clipboardExpireHours)
        .put("clipboardImages", clipboardImages)
        .put("perAppSensitive", perAppSensitive)
        .put("sensitivePackages", JSONArray(sensitivePackages().toList()))

    fun importData(json: JSONObject) {
        if (json.has("numberRow")) showNumberRow = json.optBoolean("numberRow")
        if (json.has("keyPreview")) keyPreview = json.optBoolean("keyPreview")
        if (json.has("appearance")) appearance = Appearance.from(json.optString("appearance"))
        if (json.has("mascot")) mascot = json.optBoolean("mascot")
        if (json.has("mascotPlacement")) {
            mascotPlacement = MascotPlacement.from(json.optString("mascotPlacement"))
        }
        if (json.has("mascotColor")) mascotColor = MascotColor.from(json.optString("mascotColor"))
        if (json.has("mascotOffsetX")) mascotOffsetX = json.optInt("mascotOffsetX", 0)
        if (json.has("mascotOffsetY")) mascotOffsetY = json.optInt("mascotOffsetY", -3)
        if (json.has("mascotScale")) mascotScale = json.optInt("mascotScale", 100)
        if (json.has("suggestionDock")) suggestionDock = json.optBoolean("suggestionDock", true)
        if (json.has("floatingBar")) floatingBar = json.optBoolean("floatingBar", false)
        if (json.has("haptics")) haptics = json.optBoolean("haptics")
        if (json.has("sound")) sound = json.optBoolean("sound")
        if (json.has("autoCapitalize")) autoCapitalize = json.optBoolean("autoCapitalize")
        if (json.has("capitalizeFirstLetter")) {
            capitalizeFirstLetter = json.optBoolean("capitalizeFirstLetter")
        }
        if (json.has("nextWordSuggestions")) {
            nextWordSuggestions = json.optBoolean("nextWordSuggestions")
        }
        if (json.has("emojiPrediction")) emojiPrediction = json.optBoolean("emojiPrediction")
        if (json.has("blockInappropriate")) {
            blockInappropriate = json.optBoolean("blockInappropriate")
        }
        if (json.has("emojiTone")) emojiTone = json.optInt("emojiTone", 0)
        if (json.has("keyboardHeightPercent")) {
            keyboardHeightPercent = json.optInt("keyboardHeightPercent", 100)
        }
        if (json.has("keyboardPadding")) keyboardPadding = json.optInt("keyboardPadding", 0)
        if (json.has("floatingKeyboard")) floatingKeyboard = json.optBoolean("floatingKeyboard")
        if (json.has("clipboardEnabled")) clipboardEnabled = json.optBoolean("clipboardEnabled")
        if (json.has("clipboardExpireHours")) {
            clipboardExpireHours = json.optInt("clipboardExpireHours", 24)
        }
        if (json.has("clipboardImages")) clipboardImages = json.optBoolean("clipboardImages")
        if (json.has("perAppSensitive")) perAppSensitive = json.optBoolean("perAppSensitive")
        if (json.has("sensitivePackages")) {
            val arr = json.optJSONArray("sensitivePackages")
            if (arr != null) {
                val set = HashSet<String>()
                for (i in 0 until minOf(arr.length(), 300)) {
                    val pkg = arr.optString(i, "")
                    if (pkg.isNotEmpty() && pkg.length <= 200) set.add(pkg)
                }
                setSensitivePackages(set)
            }
        }
    }

    /**
     * Version 1 was the first release. Version 2 adds the 0.4 settings.
     * Every new value already has a sensible default in its getter, so this only
     * records the version and fixes values that changed meaning.
     */
    private fun migrate() {
        val version = prefs.getInt(KEY_VERSION, 0)
        if (version >= PREFS_VERSION) return
        val editor = prefs.edit()
        if (version < 1) {
            // First run of the old build: nothing to move, just start from defaults.
            if (!prefs.contains(KEY_MASCOT)) editor.putBoolean(KEY_MASCOT, true)
            if (!prefs.contains(KEY_MASCOT_PLACEMENT)) {
                editor.putString(KEY_MASCOT_PLACEMENT, MascotPlacement.CURSOR.key)
            }
        }
        if (version < 2) {
            if (!prefs.contains(KEY_MASCOT_PLACEMENT)) {
                editor.putString(KEY_MASCOT_PLACEMENT, MascotPlacement.CURSOR.key)
            }
            if (!prefs.contains(KEY_HAPTICS)) editor.putBoolean(KEY_HAPTICS, true)
            if (!prefs.contains(KEY_CLIP_IMAGES)) editor.putBoolean(KEY_CLIP_IMAGES, true)
            if (!prefs.contains(KEY_PER_APP_SENSITIVE)) {
                editor.putBoolean(KEY_PER_APP_SENSITIVE, true)
            }
            if (!prefs.contains(KEY_AUTO_CAP)) editor.putBoolean(KEY_AUTO_CAP, true)
            if (!prefs.contains(KEY_CAP_FIRST)) editor.putBoolean(KEY_CAP_FIRST, true)
        }
        if (version < 3) {
            // The mascot used to sit in a band above the keys, which pushed the whole
            // keyboard down. It now defaults to sitting at the cursor.
            editor.putString(KEY_MASCOT_PLACEMENT, MascotPlacement.CURSOR.key)
        }
        editor.putInt(KEY_VERSION, PREFS_VERSION).apply()
    }

    private companion object {
        const val PREFS_VERSION = 3
        const val KEY_VERSION = "settings_version"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_PREVIEW = "key_preview"
        const val KEY_APPEARANCE = "appearance"
        const val KEY_MASCOT = "mascot"
        const val KEY_MASCOT_PLACEMENT = "mascot_placement"
        const val KEY_MASCOT_COLOR = "mascot_color"
        const val KEY_MASCOT_OFFSET_X = "mascot_offset_x"
        const val KEY_MASCOT_OFFSET_Y = "mascot_offset_y"
        const val KEY_MASCOT_SCALE = "mascot_scale"
        const val KEY_SUGGESTION_DOCK = "suggestion_dock"
        const val KEY_HAPTICS = "haptics"
        const val KEY_SOUND = "sound"
        const val KEY_AUTO_CAP = "auto_capitalize"
        const val KEY_CAP_FIRST = "capitalize_first_letter"
        const val KEY_NEXT_WORD = "next_word_suggestions"
        const val KEY_EMOJI_PREDICTION = "emoji_prediction"
        const val KEY_BLOCK_INAPPROPRIATE = "block_inappropriate"
        const val KEY_EMOJI_TONE = "emoji_tone"
        const val KEY_HEIGHT_PERCENT = "keyboard_height_percent"
        const val KEY_BOTTOM_PADDING = "keyboard_bottom_padding"
        const val KEY_FLOATING = "floating_keyboard"
        const val KEY_FLOATING_BAR = "floating_bar_v2"
        const val KEY_FLOATING_X = "floating_x"
        const val KEY_FLOATING_Y = "floating_y"
        const val KEY_CLIP_ENABLED = "clip_enabled"
        const val KEY_CLIP_PAUSED = "clip_paused"
        const val KEY_CLIP_EXPIRE = "clip_expire_hours"
        const val KEY_CLIP_IMAGES = "clip_images"
        const val KEY_PER_APP_SENSITIVE = "per_app_sensitive"
        const val KEY_SENSITIVE_PACKAGES = "sensitive_packages"
        }
}

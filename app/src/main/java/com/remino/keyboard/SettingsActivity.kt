package com.remino.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

/** Setup, options, typing stats and encrypted backup. */
class SettingsActivity : Activity() {

    private enum class SettingsPage {
        HOME, APPEARANCE, KEYBOARD, REO, TYPING, CLIPBOARD, PRIVACY, SETUP, ACTIVITY, DATA, UPDATES,
    }

    private lateinit var stats: TypingStats
    private lateinit var options: KeyboardPrefs
    private lateinit var pageRoot: LinearLayout
    private var page = SettingsPage.HOME

    private lateinit var statusChip: TextView
    private lateinit var enabledStatus: TextView
    private lateinit var selectedStatus: TextView

    private lateinit var todayValue: TextView
    private lateinit var totalValue: TextView
    private lateinit var booksValue: TextView
    private lateinit var streakValue: TextView
    private lateinit var bestValue: TextView
    private lateinit var averageValue: TextView
    private lateinit var chart: BarChart
    private lateinit var spark: SparkBars

    private lateinit var expireButton: Button
    private lateinit var clipPreview: LinearLayout

    private lateinit var heightLabel: TextView
    private lateinit var paddingLabel: TextView
    private val mascotChips = ArrayList<TextView>()
    private val appearanceChips = ArrayList<TextView>()
    private var floatingSwitch: Switch? = null

    private var pendingPassword: CharArray? = null
    private var pendingImport: ByteArray? = null
    private var pendingIncludeClipboard = false

    private var bg = 0xFF0A0A0B.toInt()
    private var surface = 0xFF151518.toInt()
    private var surfaceLow = 0xFF141417.toInt()
    private var surfaceHigh = 0xFF232329.toInt()
    private var raised = 0xFF1F1F24.toInt()
    private var hair = 0xFF2A2A30.toInt()
    private var outline = 0xFF2C2C33.toInt()
    private var textMain = 0xFFF2F2F5.toInt()
    private var textDim = 0xFF94949E.toInt()
    private var textFaint = 0xFF63636C.toInt()
    private var lime = 0xFFC8F26A.toInt()
    private var onLime = 0xFF0A0A0B.toInt()

    /** Lime used as text or a thin stroke; darker on paper so it stays legible. */
    private var signalInk = 0xFFC8F26A.toInt()

    /** True when the screen is drawn on the light "Paper" palette. */
    private var lightMode = false

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyPalette() {
        lightMode = options.appearance.theme(isSystemDark()) === KeyboardTheme.Light
        if (lightMode) {
            bg = 0xFFF6F5F1.toInt()
            surface = 0xFFFFFFFF.toInt()
            surfaceLow = 0xFFEFEEE8.toInt()
            surfaceHigh = 0xFFE6E4DC.toInt()
            raised = 0xFFFFFFFF.toInt()
            hair = 0xFFE2E0D8.toInt()
            outline = 0xFFD8D6CE.toInt()
            textMain = 0xFF121214.toInt()
            textDim = 0xFF55555D.toInt()
            textFaint = 0xFF74747C.toInt()
            lime = 0xFFC8F26A.toInt()
            onLime = 0xFF121214.toInt()
            signalInk = 0xFF5E7A12.toInt()
        } else {
            bg = 0xFF0A0A0B.toInt()
            surface = 0xFF151518.toInt()
            surfaceLow = 0xFF141417.toInt()
            surfaceHigh = 0xFF232329.toInt()
            raised = 0xFF1F1F24.toInt()
            hair = 0xFF2A2A30.toInt()
            outline = 0xFF2C2C33.toInt()
            textMain = 0xFFF2F2F5.toInt()
            textDim = 0xFF94949E.toInt()
            textFaint = 0xFF63636C.toInt()
            lime = 0xFFC8F26A.toInt()
            onLime = 0xFF0A0A0B.toInt()
            signalInk = 0xFFC8F26A.toInt()
        }
    }

    /** Paints the window, status bar and navigation bar so no dark frame shows in Paper mode. */
    private fun applyWindowPalette() {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        window.decorView.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightMode
        controller.isAppearanceLightNavigationBars = lightMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Rotation and theme changes recreate the activity; stay on the same page.
        page = savedInstanceState?.getString(KEY_PAGE)
            ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
            ?: SettingsPage.HOME
        stats = TypingStats(this)
        options = KeyboardPrefs(this)
        applyPalette()
        applyWindowPalette()

        pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            clipToPadding = false
            addView(pageRoot)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        renderPage()
    }

    /** Rebuilds the visible settings page. Pages are layers, so Back returns home. */
    private fun renderPage() {
        pageRoot.removeAllViews()
        when (page) {
            SettingsPage.HOME -> {
                pageRoot.addView(topBar())
                pageRoot.addView(hero())
                buildHome(pageRoot)
            }
            SettingsPage.ACTIVITY -> {
                pageRoot.addView(pageHeader("Activity"))
                buildActivityPage(pageRoot)
            }
            SettingsPage.APPEARANCE -> {
                pageRoot.addView(pageHeader("Appearance"))
                buildAppearancePage(pageRoot)
            }
            SettingsPage.KEYBOARD -> {
                pageRoot.addView(pageHeader("Keyboard & keys"))
                buildKeyboardPage(pageRoot)
            }
            SettingsPage.REO -> {
                pageRoot.addView(pageHeader("Reo the mascot"))
                buildReoPage(pageRoot)
            }
            SettingsPage.TYPING -> {
                pageRoot.addView(pageHeader("Typing & suggestions"))
                buildTypingPage(pageRoot)
            }
            SettingsPage.CLIPBOARD -> {
                pageRoot.addView(pageHeader("Clipboard"))
                buildClipboardPage(pageRoot)
            }
            SettingsPage.PRIVACY -> {
                pageRoot.addView(pageHeader("Privacy"))
                buildPrivacyPage(pageRoot)
            }
            SettingsPage.SETUP -> {
                pageRoot.addView(pageHeader("Set up"))
                buildSetupPage(pageRoot)
            }
            SettingsPage.DATA -> {
                pageRoot.addView(pageHeader("Backup & data"))
                buildDataPage(pageRoot)
            }
            SettingsPage.UPDATES -> {
                pageRoot.addView(pageHeader("Updates"))
                buildUpdatesPage(pageRoot)
            }
        }
        refreshPageData()
    }

    private fun openPage(target: SettingsPage) {
        page = target
        renderPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PAGE, page.name)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (page == SettingsPage.HOME) {
            super.onBackPressed()
        } else {
            openPage(SettingsPage.HOME)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (options.appearance == Appearance.SYSTEM) recreate()
    }

    override fun onResume() {
        super.onResume()
        refreshPageData()
    }

    /** Refreshes only the widgets that exist on the page currently shown. */
    private fun refreshPageData() {
        if (::statusChip.isInitialized) {
            val active = isEnabled() && isSelected()
            statusChip.text = if (active) "Active" else "Set up"
            statusChip.setTextColor(if (active) signalInk else 0xFFB97D2A.toInt())
            statusChip.background = if (active) {
                strokeRound(0x1AC8F26A, 0x47C8F26A, 999)
            } else {
                strokeRound(0x1AE8B063, 0x47E8B063, 999)
            }
        }
        if (::enabledStatus.isInitialized) {
            enabledStatus.text = if (isEnabled()) "Enabled" else "Not enabled yet"
        }
        if (::selectedStatus.isInitialized) {
            selectedStatus.text = if (isSelected()) "In use" else "Not selected yet"
        }
        if (::todayValue.isInitialized) {
            todayValue.text = String.format(Locale.getDefault(), "%,d", stats.todayWords())
        }
        // Home has the hero count and sparkline; the Activity page has the full
        // block. Each is refreshed only when it is on screen.
        if (::chart.isInitialized || ::spark.isInitialized) refreshStats()
        if (::clipPreview.isInitialized) refreshClipboardPreview()
    }

    // ------------------------------------------------------------------ top

    private fun isEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isSelected(): Boolean {
        val current = Settings.Secure.getString(
            contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return false
        return current.startsWith(packageName)
    }

    private fun topBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40),
        )
        addView(label("Remino", 21f, textMain, true).also {
            it.letterSpacing = -0.04f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusChip = label("Set up", 12f, signalInk, true).apply {
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = strokeRound(0x1AC8F26A, 0x47C8F26A, 999)
        }
        addView(statusChip)
    }

    private fun hero(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        layoutParams = wideTop(dp(20))
        val left = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        todayValue = label("0", 58f, textMain, true).apply {
            letterSpacing = -0.05f
            includeFontPadding = false
        }
        left.addView(todayValue)
        left.addView(label("WORDS TODAY", 10f, textDim, true).also {
            it.letterSpacing = 0.2f
            it.topPad(8)
        })
        addView(left)
        spark = SparkBars(this@SettingsActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(56))
            color = raised
            highlight = lime
        }
        addView(spark)
    }

    // ------------------------------------------------------------- keyboard

    private fun buildHome(pane: LinearLayout) {
        val card = card()
        card.addView(sectionHeader("Settings"))
        val items = listOf(
            Triple("Appearance", "Light, dark, or follow the phone", SettingsPage.APPEARANCE),
            Triple("Keyboard & keys", "Height, number row, haptics, floating", SettingsPage.KEYBOARD),
            Triple("Reo the mascot", "Placement, colour and nudging", SettingsPage.REO),
            Triple("Typing & suggestions", "Capitalization, predictions, emoji", SettingsPage.TYPING),
            Triple("Clipboard", "History, images and expiry", SettingsPage.CLIPBOARD),
            Triple("Privacy", "Private apps and sensitive fields", SettingsPage.PRIVACY),
            Triple("Set up", "Enable Remino, choose it, test it", SettingsPage.SETUP),
        )
        items.forEachIndexed { i, (title, subtitle, target) ->
            if (i > 0) card.addView(divider())
            card.addView(categoryRow(title, subtitle, iconFor(target), target))
        }
        pane.addView(card)

        val more = card()
        more.addView(sectionHeader("Your data"))
        more.addView(categoryRow("Activity & stats", "Words typed, streak and history", R.drawable.ic_clock, SettingsPage.ACTIVITY))
        more.addView(divider())
        more.addView(categoryRow("Backup & data", "Export or restore an encrypted backup", R.drawable.ic_grid, SettingsPage.DATA))
        more.addView(divider())
        more.addView(categoryRow("Updates", "Get the latest Remino release", R.drawable.ic_send, SettingsPage.UPDATES))
        pane.addView(more)
    }

    private fun iconFor(target: SettingsPage): Int = when (target) {
        SettingsPage.APPEARANCE -> R.drawable.ic_theme
        SettingsPage.KEYBOARD -> R.drawable.ic_sliders
        SettingsPage.REO -> R.drawable.ic_paw
        SettingsPage.TYPING -> R.drawable.ic_text
        SettingsPage.CLIPBOARD -> R.drawable.ic_clipboard
        SettingsPage.PRIVACY -> R.drawable.ic_person
        SettingsPage.SETUP -> R.drawable.ic_enter
        else -> R.drawable.ic_grid
    }

    private fun pageHeader(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48),
        )
        addView(label("‹", 30f, textMain, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(16), dp(2))
            setOnClickListener { openPage(SettingsPage.HOME) }
        })
        addView(label(title, 22f, textMain, true).also {
            it.letterSpacing = -0.03f
            it.gravity = Gravity.CENTER_VERTICAL
        })
    }

    private fun categoryRow(
        title: String,
        subtitle: String,
        icon: Int,
        target: SettingsPage,
    ): LinearLayout = rowShell {
        val iconView = ImageView(this@SettingsActivity).apply {
            setImageResource(icon)
            setColorFilter(textDim)
        }
        addView(iconView, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(13) })
        val body = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        body.addView(label(title, 14f, textMain, true))
        body.addView(label(subtitle, 11f, textFaint, false).also { it.topPad(2) })
        addView(body)
        addView(label("›", 22f, textFaint, false))
    }.apply {
        isClickable = true
        setOnClickListener { openPage(target) }
    }

    private fun buildAppearancePage(pane: LinearLayout) {
        val look = card()
        look.addView(sectionHeader("Theme"))
        look.addView(
            label(
                "Auto follows the phone. Light uses the warm Paper palette; Dark uses Ink.",
                12f, textDim, false,
            ).also { it.setPadding(dp(15), 0, dp(15), dp(10)) },
        )
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(15), 0, dp(15), dp(14))
        }
        appearanceChips.clear()
        Appearance.entries.forEach { mode ->
            val chip = label(mode.label, 12f, textDim, true).apply {
                setPadding(dp(15), dp(8), dp(15), dp(8))
                setOnClickListener {
                    if (options.appearance == mode) return@setOnClickListener
                    options.appearance = mode
                    recreate()
                }
            }
            appearanceChips.add(chip)
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) },
            )
        }
        look.addView(chipRow)
        updateAppearanceChips()
        pane.addView(look)
    }

    private fun buildKeyboardPage(pane: LinearLayout) {
        val keys = card()
        keys.addView(sectionHeader("Keys"))
        keys.addView(switchRow("Number row", "Digits above the letters", options.showNumberRow) {
            options.showNumberRow = it
        })
        keys.addView(switchRow("Key preview", "Bubble above the key you press", options.keyPreview) {
            options.keyPreview = it
        })
        keys.addView(switchRow("Haptics", "A short buzz on every key", options.haptics) {
            options.haptics = it
        })
        keys.addView(switchRow("Sound", "A soft click on every key", options.sound) {
            options.sound = it
        })
        pane.addView(keys)

        val size = card()
        size.addView(sectionHeader("Size"))
        heightLabel = label("", 13f, textDim, false)
        size.addView(sliderRow("Keyboard height", heightLabel, 80, 130, options.keyboardHeightPercent) {
            options.keyboardHeightPercent = it
            heightLabel.text = "${it}%"
        })
        heightLabel.text = "${options.keyboardHeightPercent}%"
        paddingLabel = label("", 13f, textDim, false)
        size.addView(sliderRow("Bottom padding", paddingLabel, 0, 24, options.keyboardPadding) {
            options.keyboardPadding = it
            paddingLabel.text = "${it}dp"
        })
        paddingLabel.text = "${options.keyboardPadding}dp"
        pane.addView(size)

        val floating = card()
        floating.addView(sectionHeader("Floating"))
        val floatingRow = switchRow(
            "Floating keyboard",
            "A movable keyboard window over the app. Needs Display over other apps",
            options.floatingKeyboard,
        ) { requestFloating(it) }
        floatingSwitch = floatingRow.getChildAt(1) as? Switch
        floating.addView(floatingRow)
        pane.addView(floating)
    }

    private fun buildReoPage(pane: LinearLayout) {
        val keys = card()
        keys.addView(sectionHeader("Reo"))
        keys.addView(switchRow("Reo", "Your little companion who keeps the caret company", options.mascot) {
            options.mascot = it
            updateMascotChips()
        })
        keys.addView(mascotPlacementRow())
        val scaleLabel = label("", 13f, textDim, false)
        keys.addView(
            sliderRow("Reo size (at cursor)", scaleLabel, 70, 140, options.mascotScale) {
                options.mascotScale = it
                scaleLabel.text = "${it}%"
            },
        )
        scaleLabel.text = "${options.mascotScale}%"
        pane.addView(keys)
        val note = card()
        note.addView(
            label(
                "At cursor draws Reo beside the caret, and the size slider only scales that " +
                    "at-cursor Reo. Toolbar keeps Reo inside the rounded suggestion card. " +
                    "Above adds a small strip above the keys.",
                12f, textDim, false,
            ).also { it.setPadding(dp(15), dp(4), dp(15), dp(8)) },
        )
        pane.addView(note)
    }

    private fun buildTypingPage(pane: LinearLayout) {
        val typing = card()
        typing.addView(sectionHeader("Typing"))
        typing.addView(
            switchRow(
                "Auto-capitalize",
                "Capitalize the start of a new sentence",
                options.autoCapitalize,
            ) { options.autoCapitalize = it },
        )
        typing.addView(
            switchRow(
                "Capitalize first letter",
                "Start a new field with a capital",
                options.capitalizeFirstLetter,
            ) { options.capitalizeFirstLetter = it },
        )
        typing.addView(
            switchRow(
                "Next-word suggestions",
                "Suggest the word that usually follows",
                options.nextWordSuggestions,
            ) { options.nextWordSuggestions = it },
        )
        typing.addView(
            switchRow(
                "Emoji prediction",
                "Suggest emoji as you type",
                options.emojiPrediction,
            ) { options.emojiPrediction = it },
        )
        pane.addView(typing)

        val suggestions = card()
        suggestions.addView(sectionHeader("Suggestions"))
        suggestions.addView(
            switchRow(
                "Suggestion card",
                "A floating card above the keys with Reo and the options curtain",
                options.suggestionDock,
            ) { options.suggestionDock = it },
        )
        suggestions.addView(
            switchRow(
                "Block inappropriate suggestions",
                "Filter explicit words from the suggestion bar",
                options.blockInappropriate,
            ) { options.blockInappropriate = it },
        )
        suggestions.addView(emojiToneRow())
        suggestions.addView(
            label(
                "Long-press a suggestion on the keyboard to remove it.",
                11f, textFaint, false,
            ).also { it.topPad(12) },
        )
        suggestions.addView(
            pill("Restore hidden suggestions", tonal = true) {
                val hidden = HiddenWords(this)
                if (hidden.count() == 0) {
                    toast("No hidden suggestions.")
                } else {
                    hidden.clear()
                    toast("Hidden suggestions restored.")
                }
            },
        )
        pane.addView(suggestions)
    }

    private fun buildClipboardPage(pane: LinearLayout) {
        val clip = card()
        clip.addView(sectionHeader("Clipboard"))
        clip.addView(
            switchRow(
                "Save clipboard history",
                "Kept encrypted on this phone. Passwords and private fields are skipped.",
                options.clipboardEnabled,
            ) { options.clipboardEnabled = it },
        )
        clip.addView(
            switchRow(
                "Save copied images",
                "Encrypted the same way, with a 10 MB cap",
                options.clipboardImages,
            ) { options.clipboardImages = it },
        )
        clipPreview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wideTop(dp(12))
        }
        clip.addView(clipPreview)
        expireButton = pill(expireLabel()) { showExpireDialog() }
        clip.addView(expireButton)
        clip.addView(pill("Clear all clipboard history", tonal = true) { confirmClearClipboard() })
        pane.addView(clip)
    }

    private fun buildPrivacyPage(pane: LinearLayout) {
        val privacy = card()
        privacy.addView(sectionHeader("Privacy"))
        privacy.addView(
            switchRow(
                "Private apps",
                "Treat chosen apps like password fields: no suggestions, learning or clipboard",
                options.perAppSensitive,
            ) { options.perAppSensitive = it },
        )
        privacy.addView(pill("Choose private apps") { showPrivateAppsDialog() })
        pane.addView(privacy)
        val note = card()
        note.addView(
            label(
                "Nothing you type is uploaded. Suggestions and typo learning stay on this phone.",
                12f, textDim, false,
            ).also { it.setPadding(dp(15), dp(4), dp(15), dp(8)) },
        )
        pane.addView(note)
    }

    private fun buildSetupPage(pane: LinearLayout) {
        val setup = card()
        setup.addView(sectionHeader("Keyboard"))
        enabledStatus = label("", 11f, textFaint, false)
        selectedStatus = label("", 11f, textFaint, false)
        setup.addView(stepRow("Enable keyboard", enabledStatus, {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }))
        setup.addView(divider())
        setup.addView(stepRow("Set as default", selectedStatus, {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }))
        pane.addView(setup)

        val tryCard = card()
        tryCard.addView(sectionHeader("Try it"))
        val field = EditText(this).apply {
            hint = "Type here to test"
            setHintTextColor(textFaint)
            setTextColor(textMain)
            textSize = 14f
            background = roundRect(raised, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = wideTop(dp(10))
        }
        tryCard.addView(field)
        pane.addView(tryCard)
    }

    private fun buildDataPage(pane: LinearLayout) {
        val backup = card()
        backup.addView(sectionHeader("Backup"))
        backup.addView(
            label(
                "Settings, stats and learned words in one file protected by a password. " +
                    "If you forget the password the file cannot be opened.",
                12f,
                textDim,
                false,
            ).also { it.topPad(8) },
        )
        backup.addView(pill("Export backup") { showExportDialog() })
        backup.addView(pill("Import backup") { startImportPicker() })
        pane.addView(backup)
    }

    private fun buildUpdatesPage(pane: LinearLayout) {
        pane.addView(updateCard())
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
        val versionCode = installedVersionCode()
        val about = card()
        about.addView(sectionHeader("About"))
        about.addView(
            label("Remino v$version ($versionCode)", 13f, textDim, false).also { it.topPad(6) },
        )
        about.addView(
            label(
                "Remino is distributed outside Google Play. The button above opens the " +
                    "download page in your browser; Chrome handles the download and Android " +
                    "installs the APK. The app itself never uses the internet.",
                12f, textFaint, false,
            ).also { it.topPad(6) },
        )
        pane.addView(about)
    }

    private lateinit var mascotPreview: MascotPreview
    private val mascotColorDots = ArrayList<TextView>()

    private fun mascotPlacementRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), 0, dp(15), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        mascotChips.clear()
        val chipRow = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            MascotPlacement.TOOLBAR to "Toolbar",
            MascotPlacement.ABOVE to "Above",
            MascotPlacement.CURSOR to "At cursor",
        ).forEach { (placement, name) ->
            val chip = label(name, 11f, textDim, true).apply {
                setPadding(dp(11), dp(6), dp(11), dp(6))
                setOnClickListener { selectMascotPlacement(placement) }
            }
            mascotChips.add(chip)
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(7) },
            )
        }
        addView(chipRow)

        addView(label("Reo's colour", 11f, textDim, true).also { it.topPad(13) })
        // Two rows of four, so the swatches still fit on a narrow phone.
        val colorWrap = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, 0)
        }
        mascotColorDots.clear()
        MascotColor.entries.chunked(4).forEach { chunk ->
            val colorRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            chunk.forEach { color ->
                val dot = TextView(this@SettingsActivity).apply {
                    text = "✓"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(onLime)
                    background = roundRect(color.body, 999)
                    contentDescription = color.label
                    setOnClickListener {
                        options.mascotColor = color
                        updateMascotChips()
                    }
                }
                mascotColorDots.add(dot)
                colorRow.addView(
                    dot,
                    LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                        marginEnd = dp(10)
                        bottomMargin = dp(2)
                    },
                )
            }
            colorWrap.addView(colorRow)
        }
        addView(colorWrap)

        mascotPreview = MascotPreview(this@SettingsActivity).apply {
            accent = lime
            lane = raised
            face = onLime
            dim = textFaint
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62),
            ).apply { topMargin = dp(12) }
        }
        addView(mascotPreview)

        val posValue = label("", 11f, textDim, false)
        addView(sliderRow("Nudge up / down", posValue, -16, 16, options.mascotOffsetY) { v ->
            options.mascotOffsetY = v
            posValue.text = if (v == 0) "centred" else if (v < 0) "${-v} dp up" else "$v dp down"
            updateMascotChips()
        })
        val sideValue = label("", 11f, textDim, false)
        addView(sliderRow("Nudge left / right", sideValue, -16, 16, options.mascotOffsetX) { v ->
            options.mascotOffsetX = v
            sideValue.text = if (v == 0) "centred" else if (v < 0) "${-v} dp left" else "$v dp right"
            updateMascotChips()
        })
        posValue.text = if (options.mascotOffsetY == 0) "centred" else
            if (options.mascotOffsetY < 0) "${-options.mascotOffsetY} dp up" else "${options.mascotOffsetY} dp down"
        sideValue.text = if (options.mascotOffsetX == 0) "centred" else
            if (options.mascotOffsetX < 0) "${-options.mascotOffsetX} dp left" else "${options.mascotOffsetX} dp right"
        updateMascotChips()
    }

    private fun requestFloating(on: Boolean) {
        if (on && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this, R.style.ReminoDialog)
                .setTitle("Display over other apps")
                .setMessage(
                    "The floating keyboard is drawn in its own window, so Remino needs this " +
                        "permission. Without it the normal keyboard is used. Some apps hide " +
                        "overlay windows; switch this off if the keyboard does not show there. " +
                        "Drag the small bar at the top of the keyboard to move it.",
                )
                .setPositiveButton("Open settings") { _, _ ->
                    options.floatingKeyboard = true
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                .setNegativeButton("Not now") { _, _ ->
                    options.floatingKeyboard = false
                    floatingSwitch?.isChecked = false
                }
                .show()
            return
        }
        options.floatingKeyboard = on
    }

    private fun selectMascotPlacement(placement: MascotPlacement) {
        if (placement == MascotPlacement.CURSOR && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this, R.style.ReminoDialog)
                .setTitle("Display over other apps")
                .setMessage(
                    "To sit exactly on the cursor, Remino needs permission to draw a small " +
                        "overlay. Until it is granted, the mascot sits in the toolbar.",
                )
                .setPositiveButton("Open settings") { _, _ ->
                    options.mascotPlacement = placement
                    updateMascotChips()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                .setNegativeButton("Use Toolbar") { _, _ ->
                    options.mascotPlacement = MascotPlacement.TOOLBAR
                    updateMascotChips()
                }
                .show()
            return
        }
        options.mascotPlacement = placement
        updateMascotChips()
    }

    private fun updateAppearanceChips() {
        val selected = options.appearance
        appearanceChips.forEachIndexed { i, chip ->
            val on = Appearance.entries.getOrNull(i) == selected
            chip.setTextColor(if (on) onLime else textDim)
            chip.background = if (on) {
                roundRect(lime, 999)
            } else {
                strokeRound(0x00000000, hair, 999)
            }
        }
    }

    private fun updateMascotChips() {
        val enabled = options.mascot
        val selected = options.mascotPlacement
        val order = listOf(MascotPlacement.TOOLBAR, MascotPlacement.ABOVE, MascotPlacement.CURSOR)
        mascotChips.forEachIndexed { i, chip ->
            val on = enabled && order.getOrNull(i) == selected
            chip.setTextColor(if (on) signalInk else if (enabled) textDim else textFaint)
            chip.background = if (on) {
                strokeRound(0x14C8F26A, 0x73C8F26A, 999)
            } else {
                strokeRound(0x00000000, hair, 999)
            }
            chip.isEnabled = enabled
            chip.alpha = if (enabled) 1f else 0.45f
        }
        val color = options.mascotColor
        mascotColorDots.forEachIndexed { i, dot ->
            val on = MascotColor.entries.getOrNull(i) == color
            dot.text = if (on) "✓" else ""
            dot.alpha = if (enabled) 1f else 0.45f
        }
        if (::mascotPreview.isInitialized) {
            mascotPreview.placement = selected
            mascotPreview.mascotOn = enabled
            mascotPreview.accent = color.body
            mascotPreview.face = color.ink
            mascotPreview.offsetX = options.mascotOffsetX
            mascotPreview.offsetY = options.mascotOffsetY
            mascotPreview.invalidate()
        }
    }

    private fun emojiToneRow(): LinearLayout = rowShell {
        val left = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(label("Emoji skin tone", 14f, textMain, false))
        left.addView(label(toneName(options.emojiTone), 11f, textFaint, false))
        addView(left)
        addView(label("Change", 12f, signalInk, true).apply {
            setOnClickListener { showToneDialog() }
        })
    }

    private fun toneName(tone: Int): String = when (tone) {
        1 -> "Light"
        2 -> "Medium light"
        3 -> "Medium"
        4 -> "Medium dark"
        5 -> "Dark"
        else -> "No tone"
    }

    private fun showToneDialog() {
        val names = arrayOf("No tone", "Light", "Medium light", "Medium", "Medium dark", "Dark")
        AlertDialog.Builder(this, R.style.ReminoDialog)
            .setTitle("Emoji skin tone")
            .setSingleChoiceItems(names, options.emojiTone) { dialog, which ->
                options.emojiTone = which
                dialog.dismiss()
            }
            .show()
    }

    private fun showPrivateAppsDialog() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase(Locale.getDefault()) }
        } catch (e: Exception) {
            emptyList()
        }
        val chosen = options.sensitivePackages().toMutableSet()
        val checks = ArrayList<Pair<String, CheckBox>>()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val listScroll = ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380),
            )
        }
        for ((pkg, name) in apps) {
            val check = CheckBox(this).apply {
                text = name
                isChecked = pkg in chosen
                setTextColor(textMain)
                textSize = 14f
            }
            checks.add(pkg to check)
            box.addView(check)
        }
        AlertDialog.Builder(this, R.style.ReminoDialog)
            .setTitle("Private apps")
            .setMessage("No suggestions, learning or clipboard saving in these apps.")
            .setView(listScroll)
            .setPositiveButton("Save") { _, _ ->
                val set = HashSet<String>()
                for ((pkg, check) in checks) if (check.isChecked) set.add(pkg)
                options.setSensitivePackages(set)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCard(): LinearLayout = card().apply {
        val row = rowShell {
            val icon = ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_send)
                imageTintList = ColorStateList.valueOf(lime)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            addView(icon)
            val body = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            body.addView(label("Get the latest version", 14f, textMain, true))
            body.addView(label("Downloads open in Chrome", 11f, textFaint, false))
            addView(body)
        }
        row.setOnClickListener { openUpdatePage() }
        addView(row)
    }

    /** Opens the Cloudflare-hosted download page; Chrome does the download. */
    private fun openUpdatePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL)))
        } catch (e: Exception) {
            toast("No browser found. Open $UPDATE_URL")
        }
    }

    private fun installedVersionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (e: Exception) {
        0L
    }

    // ------------------------------------------------------------- activity

    private fun buildActivityPage(pane: LinearLayout) {
        val statsCard = card()
        statsCard.addView(sectionHeader("Typing"))
        val installed = formatInstallDay()
        statsCard.addView(
            label(
                if (installed.isEmpty()) "Since you installed" else "Since you installed · $installed",
                12f, textDim, false,
            ).also { it.topPad(10) },
        )
        totalValue = label("0 words", 30f, textMain, true).also {
            it.letterSpacing = -0.03f
        }
        statsCard.addView(totalValue)
        booksValue = label("0.0 books", 13f, textDim, false)
        statsCard.addView(booksValue)

        chart = BarChart(this).apply {
            barColor = raised
            highlightColor = lime
            textColor = textFaint
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }
        statsCard.addView(label("Last 7 days", 12f, textDim, false))
        statsCard.addView(chart)

        val three = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wideTop(dp(14))
        }
        val streakBlock = metricBlock("Streak", emptyLabel())
        streakValue = streakBlock.findViewWithTag("value") as TextView
        three.addView(streakBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b1 = metricBlock("Best day", emptyLabel())
        bestValue = b1.findViewWithTag("value") as TextView
        three.addView(b1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b2 = metricBlock("Average", emptyLabel())
        averageValue = b2.findViewWithTag("value") as TextView
        three.addView(b2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statsCard.addView(three)
        statsCard.addView(
            label("Counts only. Your messages are never saved.", 11f, textFaint, false).also {
                it.topPad(10)
            },
        )
        statsCard.addView(
            pill("Reset stats", tonal = true) {
                AlertDialog.Builder(this, R.style.ReminoDialog)
                    .setTitle("Reset stats")
                    .setMessage(
                        "This clears words typed, the daily chart and the streak. " +
                            "Your learned words and settings stay as they are.",
                    )
                    .setPositiveButton("Reset") { _, _ ->
                        stats.reset()
                        refreshStats()
                        toast("Stats reset.")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
        )
        pane.addView(statsCard)
    }

    private fun emptyLabel(): TextView = label("", 20f, textMain, true)

    private fun metricBlock(title: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, dp(8), 0)
        addView(label(title, 11f, textDim, false))
        value.tag = "value"
        addView(value)
    }

    private fun refreshStats() {
        val locale = Locale.getDefault()
        val total = stats.totalWords()
        // Home and the Activity page exist one at a time, so guard every widget.
        if (::todayValue.isInitialized) {
            todayValue.text = String.format(locale, "%,d", stats.todayWords())
        }
        if (::totalValue.isInitialized) {
            totalValue.text = String.format(locale, "%,d words", total)
        }
        if (::booksValue.isInitialized) {
            booksValue.text = String.format(locale, "%.1f books", StatsMath.books(total))
        }
        if (::streakValue.isInitialized) {
            streakValue.text = String.format(locale, "%d days", stats.streak())
        }
        if (::bestValue.isInitialized) {
            bestValue.text = String.format(locale, "%,d", stats.bestDay())
        }
        if (::averageValue.isInitialized) {
            averageValue.text = String.format(locale, "%,d", stats.averagePerActiveDay())
        }
        val week = stats.lastDays(7).reversed()
        if (::chart.isInitialized) {
            chart.labels = lastSevenLabels()
            chart.values = week
        }
        if (::spark.isInitialized) {
            spark.values = week.map { it.toFloat() }
        }
    }

    private fun lastSevenLabels(): List<String> {
        val fmt = SimpleDateFormat("EEE", Locale.getDefault())
        return (6 downTo 0).map { off ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -off)
            fmt.format(cal.time).take(1)
        }
    }

    private fun refreshClipboardPreview() {
        clipPreview.removeAllViews()
        val items = ClipboardStore.get(this).items().take(2)
        if (items.isEmpty()) {
            clipPreview.addView(
                label("Copied text will appear here", 12f, textFaint, false).also {
                    it.setPadding(0, dp(4), 0, dp(4))
                },
            )
            return
        }
        items.forEachIndexed { index, item ->
            val cardView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = if (index == 0) {
                    strokeRound(surface, 0x40C8F26A, 15)
                } else {
                    roundRect(surface, 15)
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = if (index == 0) dp(9) else 0 }
            }
            cardView.addView(
                label(item.text.replace('\n', ' '), 12f, textMain, false).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.15f)
                },
            )
            cardView.addView(label(relativeTime(item.time), 10f, textFaint, false).also {
                it.topPad(8)
            })
            clipPreview.addView(cardView)
        }
    }

    private fun formatInstallDay(): String {
        val raw = stats.installDay()
        if (raw.isEmpty()) return ""
        return try {
            val inFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            outFormat.format(inFormat.parse(raw)!!)
        } catch (e: Exception) {
            ""
        }
    }

    private fun relativeTime(time: Long): String {
        val diff = System.currentTimeMillis() - time
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }

    // ------------------------------------------------------------- backup

    private fun passwordField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(textMain)
        setHintTextColor(textDim)
    }

    private fun dialogBox(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        for (v in views) addView(v)
    }

    private fun showExportDialog() {
        val first = passwordField("Password (at least 8 characters)")
        val second = passwordField("Repeat password")
        val includeClip = CheckBox(this).apply {
            text = "Include clipboard history"
            setTextColor(textMain)
        }
        AlertDialog.Builder(this, R.style.ReminoDialog)
            .setTitle("Protect the backup")
            .setMessage("You will need this password to restore the backup. It cannot be recovered.")
            .setView(dialogBox(first, second, includeClip))
            .setPositiveButton("Choose file") { _, _ ->
                val a = first.text.toString()
                val b = second.text.toString()
                if (a.length < 8) {
                    toast("Use at least 8 characters.")
                } else if (a != b) {
                    toast("The passwords do not match.")
                } else {
                    pendingPassword = a.toCharArray()
                    pendingIncludeClipboard = includeClip.isChecked
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, "remaino-backup.rmbk")
                    }
                    startActivityForResult(intent, REQ_EXPORT)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            pendingPassword?.fill('\u0000')
            pendingPassword = null
            return
        }
        when (requestCode) {
            REQ_EXPORT -> {
                val password = pendingPassword
                pendingPassword = null
                if (password != null) runExport(uri, password, pendingIncludeClipboard)
            }
            REQ_IMPORT -> {
                val bytes = try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            total += read
                            if (total > BackupCrypto.MAX_SIZE) {
                                toast("That backup file is too large.")
                                return
                            }
                            out.write(buf, 0, read)
                        }
                        out.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                }
                if (bytes == null) {
                    toast("Could not read that file.")
                } else {
                    pendingImport = bytes
                    showImportDialog()
                }
            }
        }
    }

    private fun showImportDialog() {
        val field = passwordField("Backup password")
        AlertDialog.Builder(this, R.style.ReminoDialog)
            .setTitle("Restore backup")
            .setMessage("This replaces your current settings, stats and learned words.")
            .setView(dialogBox(field))
            .setPositiveButton("Restore") { _, _ ->
                val data = pendingImport
                pendingImport = null
                if (data != null) runImport(data, field.text.toString().toCharArray())
            }
            .setNegativeButton("Cancel") { _, _ -> pendingImport = null }
            .show()
    }

    private fun runExport(uri: Uri, password: CharArray, includeClipboard: Boolean) {
        toast("Saving backup...")
        Thread {
            val message = try {
                val bytes = BackupCrypto.encrypt(BackupManager.buildJson(this, includeClipboard), password)
                val out = contentResolver.openOutputStream(uri)
                    ?: throw BackupException("Could not write the file.")
                out.use { it.write(bytes) }
                "Backup saved."
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            } finally {
                password.fill('\u0000')
            }
            runOnUiThread { toast(message) }
        }.start()
    }

    private fun runImport(data: ByteArray, password: CharArray) {
        toast("Restoring backup...")
        Thread {
            var root: JSONObject? = null
            var error: String? = null
            try {
                // Decrypt and validate before touching the UI or any saved data.
                root = BackupManager.parse(BackupCrypto.decrypt(data, password))
            } catch (e: BackupException) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not read the backup."
            } finally {
                password.fill('\u0000')
            }
            val finalRoot = root
            val finalError = error
            runOnUiThread {
                if (finalRoot == null) {
                    toast(finalError ?: "Restore failed.")
                } else {
                    try {
                        BackupManager.apply(this, finalRoot)
                        toast("Backup restored.")
                        recreate()
                    } catch (e: Exception) {
                        toast(e.message ?: "Restore failed.")
                    }
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ clipboard

    private val expireChoices = intArrayOf(1, 24, 168, 0)
    private val expireNames = arrayOf("1 hour", "24 hours", "7 days", "Never")

    private fun expireLabel(): String {
        val i = expireChoices.indexOf(options.clipboardExpireHours)
        val name = if (i >= 0) expireNames[i] else "${options.clipboardExpireHours} hours"
        return "Delete unpinned after: $name"
    }

    private fun showExpireDialog() {
        AlertDialog.Builder(this, R.style.ReminoDialog)
            .setTitle("Delete unpinned items after")
            .setItems(expireNames) { _, which ->
                options.clipboardExpireHours = expireChoices[which]
                expireButton.text = expireLabel()
            }
            .show()
    }

    private fun confirmClearClipboard() {
        AlertDialog.Builder(this, R.style.ReminoDialog)
            .setTitle("Clear clipboard history")
            .setMessage("This deletes every saved item, including pinned ones.")
            .setPositiveButton("Clear all") { _, _ ->
                ClipboardStore.get(this).clearAll()
                refreshClipboardPreview()
                toast("Clipboard history cleared.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun TextView.topPad(dpValue: Int) {
        setPadding(paddingLeft, dp(dpValue), paddingRight, paddingBottom)
    }

    private fun label(s: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun sectionHeader(title: String): TextView =
        label(title, 16f, textMain, true).apply {
            letterSpacing = -0.01f
            setPadding(0, 0, 0, dp(6))
        }

    /** Material 3 style filled card: big radius, tonal surface, quiet outline. */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = strokeRound(surfaceLow, outline, 24)
        layoutParams = wideTop(dp(14))
    }

    private fun wideTop(margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = margin }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(hair)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
        ).apply { topMargin = dp(12); bottomMargin = dp(12) }
    }

    private fun rowShell(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(0), dp(12), dp(0), dp(12))
            content()
        }

    private fun stepRow(title: String, status: TextView, onClick: () -> Unit): LinearLayout =
        rowShell {
            addView(label("✓", 12f, lime, true).apply {
                gravity = Gravity.CENTER
                background = strokeRound(0x24C8F26A, 0x00000000, 999)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(11) }
            })
            val body = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            body.addView(label(title, 14f, textMain, false))
            status.textSize = 11f
            status.setTextColor(textFaint)
            body.addView(status)
            addView(body)
            addView(label("›", 18f, textFaint, false))
            setOnClickListener { onClick() }
        }

    private fun switchRow(
        title: String,
        description: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout = rowShell {
        val texts = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(label(title, 14f, textMain, false))
        texts.addView(label(description, 11f, textFaint, false).also { it.topPad(2) })
        addView(texts)
        addView(styledSwitch(checked, onChange))
    }

    private fun styledSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onLime, 0xFF8B8B94.toInt()),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(lime, surfaceHigh),
            )
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun pill(
        labelText: String,
        tonal: Boolean = false,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = labelText
        isAllCaps = false
        textSize = 14f
        setTextColor(if (tonal) signalInk else onLime)
        background = if (tonal) strokeRound(0x00000000, outline, 999) else roundRect(lime, 999)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48),
        ).apply { topMargin = dp(10) }
    }

    private fun sliderRow(
        title: String,
        value: TextView,
        min: Int,
        max: Int,
        initial: Int,
        onChange: (Int) -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(8), dp(15), dp(6))
        val head = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        head.addView(label(title, 14f, textMain, false).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(value)
        addView(head)
        addView(SeekBar(this@SettingsActivity).apply {
            this.max = max - min
            progress = initial - min
            progressTintList = ColorStateList.valueOf(lime)
            progressBackgroundTintList = ColorStateList.valueOf(surfaceHigh)
            thumbTintList = ColorStateList.valueOf(lime)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress + min)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
    }

    private fun roundRect(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun strokeRound(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radius).toFloat()
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_IMPORT = 2
        const val KEY_PAGE = "settings_page"

        /** Cloudflare-hosted download page for the latest Remino APK. */
        const val UPDATE_URL = "https://remino.pages.dev"
    }
}

/** Small seven-bar sparkline used in the hero block. */
private class SparkBars(context: Context) : View(context) {
    var values: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var color: Int = 0
    var highlight: Int = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val maxValue = max(1f, values.maxOrNull() ?: 1f)
        val gap = dp(5f)
        val barW = dp(7f)
        val totalW = values.size * barW + (values.size - 1) * gap
        val startX = width - totalW
        val base = height - dp(2f)
        val chartH = height - dp(8f)
        for (i in values.indices) {
            val h = max(dp(4f), chartH * values[i] / maxValue)
            val x = startX + i * (barW + gap)
            rect.set(x, base - h, x + barW, base)
            paint.color = if (i == values.lastIndex) highlight else color
            canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)
        }
    }
}

/** Simple bar chart for the last seven days (oldest first). */
private class BarChart(context: Context) : View(context) {
    var values: List<Long> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var labels: List<String> = emptyList()
    var barColor: Int = 0
    var highlightColor: Int = 0
    var textColor: Int = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(120f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val maxValue = max(1L, values.maxOrNull() ?: 1L)
        val slot = width.toFloat() / values.size
        val barW = slot * 0.5f
        val chartH = height - dp(22f)
        for (i in values.indices) {
            val h = max(dp(4f), chartH * values[i] / maxValue)
            val cx = slot * i + slot / 2f
            rect.set(cx - barW / 2f, chartH - h, cx + barW / 2f, chartH)
            paint.color = if (i == values.size - 1) highlightColor else barColor
            canvas.drawRoundRect(rect, dp(6f), dp(6f), paint)
            val label = labels.getOrNull(i)
            if (label != null) {
                paint.color = textColor
                paint.textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics,
                )
                canvas.drawText(label, cx, height - dp(5f), paint)
            }
        }
    }
}

/**
 * Small live preview under the mascot switch. Shows the companion in the chosen
 * placement so "Toolbar / Above / At cursor" means something before typing.
 */
private class MascotPreview(context: Context) : View(context) {
    var accent: Int = 0
    var lane: Int = 0
    var face: Int = 0
    var dim: Int = 0
    var placement: MascotPlacement = MascotPlacement.TOOLBAR
    var mascotOn: Boolean = true
    var offsetX: Int = 0
    var offsetY: Int = -3

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmp = RectF()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = android.os.SystemClock.uptimeMillis()

        val barH = dp(22f)
        val centerY = if (placement == MascotPlacement.ABOVE) {
            (h - barH) / 2f
        } else {
            h / 2f
        }

        // Keep the preview itself quiet: one soft pill, then the mascot.
        tmp.set(dp(1f), dp(2f), w - dp(2f), h - dp(2f))
        fill.color = lane
        canvas.drawRoundRect(tmp, dp(14f), dp(14f), fill)

        if (placement == MascotPlacement.ABOVE) {
            // A slim strip so "above the keys" reads differently from the toolbar lane.
            tmp.set(dp(1f), h - barH - dp(2f), w - dp(2f), h - dp(2f))
            fill.color = withAlpha(lane, 180)
            canvas.drawRoundRect(tmp, dp(12f), dp(12f), fill)
        }

        if (!mascotOn) {
            fill.color = dim
            fill.textAlign = Paint.Align.CENTER
            fill.textSize = dp(12f)
            canvas.drawText("Reo is off", w / 2f, centerY + dp(4f), fill)
            return
        }

        val baseX = if (placement == MascotPlacement.ABOVE) w * 0.5f else w * 0.42f
        val r = dp(9f)
        val cx = (baseX + dp(offsetX.toFloat())).coerceIn(dp(18f), w - dp(18f))
        val cy = (centerY + dp(offsetY.toFloat())).coerceIn(dp(16f), h - dp(16f))
        MascotArt.draw(
            canvas = canvas,
            fill = fill,
            stroke = stroke,
            tmp = tmp,
            cx = cx,
            cy = cy,
            r = r,
            body = accent,
            onBody = face,
            now = now,
            wave = true,
            pushToX = null,
            deleting = false,
            walk = 0f,
        )

        // Caret that the mascot keeps company.
        if ((now / 450L) % 2L == 0L) {
            fill.color = accent
            tmp.set(cx + r + dp(6f), cy - dp(8f), cx + r + dp(8f), cy + dp(8f))
            canvas.drawRoundRect(tmp, dp(1f), dp(1f), fill)
        }
        postInvalidateOnAnimation()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}

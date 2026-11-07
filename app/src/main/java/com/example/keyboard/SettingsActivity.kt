package com.example.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

/** Setup screen: enable the keyboard, options, typing stats, and encrypted backup. */
class SettingsActivity : Activity() {

    private lateinit var stats: TypingStats
    private lateinit var options: KeyboardPrefs
    private lateinit var enabledStatus: TextView
    private lateinit var selectedStatus: TextView
    private lateinit var todayValue: TextView
    private lateinit var totalValue: TextView
    private lateinit var booksValue: TextView
    private lateinit var streakValue: TextView
    private lateinit var bestValue: TextView
    private lateinit var averageValue: TextView
    private lateinit var topWordsValue: TextView
    private lateinit var chart: BarChart
    private lateinit var expireButton: Button

    private var pendingPassword: CharArray? = null
    private var pendingImport: ByteArray? = null
    private var pendingIncludeClipboard = false

    private val bg = 0xFF121214.toInt()
    private val cardBg = 0xFF1C1C20.toInt()
    private val fieldBg = 0xFF2B2B31.toInt()
    private val textMain = 0xFFECECEF.toInt()
    private val textDim = 0xFFA8A8B0.toInt()
    private val accent = 0xFFF2F2F5.toInt()
    private val onAccent = 0xFF121214.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stats = TypingStats(this)
        options = KeyboardPrefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            clipToPadding = false
            addView(root)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        root.addView(label("Remaino", 28f, textMain, true))
        root.addView(label("Private, fast and dark by default.", 15f, textDim, false))

        // Setup card
        val setup = card()
        setup.addView(label("Set up", 18f, textMain, true))
        setup.addView(label("1. Enable the keyboard", 15f, textMain, false).also { it.topPad(12) })
        enabledStatus = label("", 13f, textDim, false)
        setup.addView(enabledStatus)
        setup.addView(
            pill("Open keyboard settings") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )
        setup.addView(label("2. Choose it as your keyboard", 15f, textMain, false).also { it.topPad(16) })
        selectedStatus = label("", 13f, textDim, false)
        setup.addView(selectedStatus)
        setup.addView(
            pill("Choose keyboard") {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
        )
        root.addView(setup)

        // Try it card
        val tryCard = card()
        tryCard.addView(label("Try it", 18f, textMain, true))
        val field = EditText(this).apply {
            hint = "Type here to test"
            setHintTextColor(textDim)
            setTextColor(textMain)
            background = GradientDrawable().apply {
                setColor(fieldBg)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        tryCard.addView(field)
        root.addView(tryCard)

        // Keyboard options card
        val optionsCard = card()
        optionsCard.addView(label("Keyboard", 18f, textMain, true))
        optionsCard.addView(
            switchRow("Number row", "Show the row of digits above the letters", options.showNumberRow) {
                options.showNumberRow = it
            },
        )
        optionsCard.addView(
            switchRow("Key preview", "Show a bubble above the key you press", options.keyPreview) {
                options.keyPreview = it
            },
        )
        optionsCard.addView(
            switchRow("Mascot", "A little friend who pushes the cursor back while you delete", options.mascot) {
                options.mascot = it
            },
        )
        root.addView(optionsCard)

        // Clipboard card
        val clipCard = card()
        clipCard.addView(label("Clipboard", 18f, textMain, true))
        clipCard.addView(
            switchRow(
                "Save clipboard history",
                "Kept encrypted on this phone. Passwords and private fields are skipped.",
                options.clipboardEnabled,
            ) { options.clipboardEnabled = it },
        )
        expireButton = pill(expireLabel()) { showExpireDialog() }
        clipCard.addView(expireButton)
        clipCard.addView(pill("Clear all clipboard history") { confirmClearClipboard() })
        root.addView(clipCard)

        // Stats card
        val statsCard = card()
        statsCard.addView(label("Typing stats", 18f, textMain, true))
        statsCard.addView(label("Today", 13f, textDim, false).also { it.topPad(12) })
        todayValue = label("0", 28f, textMain, true)
        statsCard.addView(todayValue)
        statsCard.addView(label("Since you installed", 13f, textDim, false).also { it.topPad(12) })
        totalValue = label("0 words", 22f, textMain, true)
        statsCard.addView(totalValue)
        booksValue = label("0.0 books", 15f, textDim, false)
        statsCard.addView(booksValue)

        chart = BarChart(this).apply {
            barColor = fieldBg
            highlightColor = accent
            textColor = textDim
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }
        statsCard.addView(label("Last 7 days", 13f, textDim, false).also { it.topPad(14) })
        statsCard.addView(chart)

        statsCard.addView(label("Streak", 13f, textDim, false).also { it.topPad(12) })
        streakValue = label("0 days", 16f, textMain, false)
        statsCard.addView(streakValue)
        statsCard.addView(label("Best day", 13f, textDim, false).also { it.topPad(10) })
        bestValue = label("0 words", 16f, textMain, false)
        statsCard.addView(bestValue)
        statsCard.addView(label("Average on days you type", 13f, textDim, false).also { it.topPad(10) })
        averageValue = label("0 words", 16f, textMain, false)
        statsCard.addView(averageValue)
        statsCard.addView(label("Most used words", 13f, textDim, false).also { it.topPad(10) })
        topWordsValue = label("None yet", 16f, textMain, false)
        statsCard.addView(topWordsValue)

        statsCard.addView(label("Counts only. Your messages are never saved.", 12f, textDim, false).also { it.topPad(12) })
        statsCard.addView(
            pill("Reset stats") {
                stats.reset()
                refreshStats()
            },
        )
        statsCard.addView(
            pill("Forget learned words") {
                WordFrequencies.get(this).clear()
                refreshStats()
            },
        )
        root.addView(statsCard)

        // Backup card
        val backup = card()
        backup.addView(label("Backup", 18f, textMain, true))
        backup.addView(
            label(
                "Save your settings, stats and learned words in one file protected by a password. " +
                    "If you forget the password the file cannot be opened.",
                13f,
                textDim,
                false,
            ).also { it.topPad(8) },
        )
        backup.addView(pill("Export backup") { showExportDialog() })
        backup.addView(pill("Import backup") { startImportPicker() })
        root.addView(backup)
    }

    override fun onResume() {
        super.onResume()
        enabledStatus.text = if (isEnabled()) "Enabled" else "Not enabled yet"
        selectedStatus.text = if (isSelected()) "In use" else "Not selected yet"
        refreshStats()
    }

    private fun refreshStats() {
        val total = stats.totalWords()
        val locale = Locale.getDefault()
        todayValue.text = String.format(locale, "%,d", stats.todayWords())
        totalValue.text = String.format(locale, "%,d words", total)
        booksValue.text = String.format(locale, "%.1f books", StatsMath.books(total))
        streakValue.text = String.format(locale, "%d days", stats.streak())
        bestValue.text = String.format(locale, "%,d words", stats.bestDay())
        averageValue.text = String.format(locale, "%,d words", stats.averagePerActiveDay())
        chart.labels = lastSevenLabels()
        chart.values = stats.lastDays(7).reversed()
        val top = WordFrequencies.get(this).top(5)
        topWordsValue.text = if (top.isEmpty()) {
            "None yet"
        } else {
            top.joinToString("\n") { "${it.first}  (${it.second})" }
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

    private fun isEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isSelected(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?: return false
        return id.startsWith("$packageName/")
    }

    // ---------- backup ----------

    private fun passwordField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(textMain)
        setHintTextColor(textDim)
    }

    private fun dialogBox(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(0))
        for (v in views) addView(v)
    }

    private fun showExportDialog() {
        val first = passwordField("Password (at least 8 characters)")
        val second = passwordField("Repeat password")
        val includeClip = CheckBox(this).apply {
            text = "Include clipboard history"
            setTextColor(textMain)
        }
        AlertDialog.Builder(this)
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
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
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
        AlertDialog.Builder(this)
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
                val out = contentResolver.openOutputStream(uri) ?: throw BackupException("Could not write the file.")
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
            var json: String? = null
            var error: String? = null
            try {
                json = BackupCrypto.decrypt(data, password)
            } catch (e: BackupException) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not read the backup."
            } finally {
                password.fill('\u0000')
            }
            val finalJson = json
            val finalError = error
            runOnUiThread {
                if (finalJson == null) {
                    toast(finalError ?: "Restore failed.")
                } else {
                    try {
                        BackupManager.apply(this, finalJson)
                        refreshStats()
                        toast("Backup restored.")
                    } catch (e: Exception) {
                        toast(e.message ?: "Restore failed.")
                    }
                }
            }
        }.start()
    }

    private val expireChoices = intArrayOf(1, 24, 168, 0)
    private val expireNames = arrayOf("1 hour", "24 hours", "7 days", "Never")

    private fun expireLabel(): String {
        val i = expireChoices.indexOf(options.clipboardExpireHours)
        val name = if (i >= 0) expireNames[i] else "${options.clipboardExpireHours} hours"
        return "Delete unpinned after: $name"
    }

    private fun showExpireDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete unpinned items after")
            .setItems(expireNames) { _, which ->
                options.clipboardExpireHours = expireChoices[which]
                expireButton.text = expireLabel()
            }
            .show()
    }

    private fun confirmClearClipboard() {
        AlertDialog.Builder(this)
            .setTitle("Clear clipboard history")
            .setMessage("This deletes every saved item, including pinned ones.")
            .setPositiveButton("Clear all") { _, _ ->
                ClipboardStore.get(this).clearAll()
                toast("Clipboard history cleared.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---------- small UI helpers ----------

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

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            setColor(cardBg)
            cornerRadius = dp(20).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) }
    }

    private fun pill(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(onAccent)
        background = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(24).toFloat()
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(10) }
    }

    private fun switchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(label(title, 15f, textMain, false))
        texts.addView(label(description, 13f, textDim, false))
        val toggle = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(texts)
        row.addView(toggle)
        return row
    }

    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_IMPORT = 2
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

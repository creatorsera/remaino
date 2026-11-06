package com.example.keyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

/** Setup screen: enable the keyboard, pick it, try it, and see your typing stats. */
class SettingsActivity : Activity() {

    private lateinit var stats: TypingStats
    private lateinit var enabledStatus: TextView
    private lateinit var selectedStatus: TextView
    private lateinit var todayValue: TextView
    private lateinit var totalValue: TextView
    private lateinit var booksValue: TextView

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

        root.addView(label("Keyboard", 28f, textMain, true))
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
        statsCard.addView(label("Counts only. Your messages are never saved.", 12f, textDim, false).also { it.topPad(12) })
        statsCard.addView(
            pill("Reset stats") {
                stats.reset()
                refreshStats()
            },
        )
        root.addView(statsCard)
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
}

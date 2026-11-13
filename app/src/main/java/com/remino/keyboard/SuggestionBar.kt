package com.remino.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The floating suggestion pill. It is a normal View hierarchy so suggestions,
 * the mascot blink and the mic only invalidate their own small areas instead of
 * the whole canvas.
 */
class SuggestionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), KeyboardChrome {

    interface Listener {
        fun onSuggestion(word: String)
        fun onSuggestionLongPress(word: String)
        fun onMic()
        fun onStatus()
        fun onMascot()
    }

    var listener: Listener? = null

    private lateinit var mascot: KeyMascotView
    private lateinit var barIcon: android.widget.ImageButton
    private lateinit var group: LinearLayout
    private lateinit var status: TextView
    private lateinit var mic: ImageButton
    private lateinit var cells: List<TextView>
    private lateinit var dividers: List<View>

    private var words: List<String> = emptyList()
    private var listening = false
    private var statusClickable = false
    private var theme = KeyboardTheme.Dark
    private var mascotColor = MascotColor.LIME
    private var docked = false

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        mascot = findViewById(R.id.mascot)
        barIcon = findViewById(R.id.bar_icon)
        group = findViewById(R.id.suggestion_group)
        status = findViewById(R.id.status)
        mic = findViewById(R.id.mic)
        cells = listOf(
            findViewById(R.id.suggestion_0),
            findViewById(R.id.suggestion_1),
            findViewById(R.id.suggestion_2),
        )
        dividers = listOf(findViewById(R.id.divider_1), findViewById(R.id.divider_2))

        cells.forEachIndexed { i, cell ->
            cell.setOnClickListener {
                words.getOrNull(i)?.let { listener?.onSuggestion(it) }
            }
            cell.setOnLongClickListener {
                words.getOrNull(i)?.let { listener?.onSuggestionLongPress(it) }
                true
            }
        }
        mascot.setOnClickListener { listener?.onMascot() }
        barIcon.setOnClickListener { listener?.onMascot() }
        mic.setOnClickListener { listener?.onMic() }
        status.setOnClickListener {
            if (statusClickable) listener?.onStatus()
        }
        applyTheme(theme)
        setSuggestions(emptyList())
    }

    override fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        background = if (docked) {
            null
        } else {
            GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.kb_bar_radius)
                setColor(theme.bar)
            }
        }
        cells.forEach { it.setTextColor(theme.text) }
        status.setTextColor(theme.text)
        dividers.forEach { it.setBackgroundColor(theme.divider) }
        mic.imageTintList = ColorStateList.valueOf(if (listening) theme.accent else theme.icon)
        mascot.setColors(mascotColor.body, mascotColor.ink, theme.accent)
        barIcon.imageTintList = ColorStateList.valueOf(mascotColor.body)
        barIcon.backgroundTintList = ColorStateList.valueOf(
            androidx.core.graphics.ColorUtils.setAlphaComponent(mascotColor.body, 42),
        )
        cells[1].typeface = medium
        cells[0].typeface = regular
        cells[2].typeface = regular
    }

    fun applyMascotColor(color: MascotColor) {
        mascotColor = color
        mascot.setColors(color.body, color.ink, theme.accent)
    }

    override fun setSuggestions(words: List<String>) {
        this.words = words.take(3)
        listening = false
        statusClickable = false
        group.visibility = VISIBLE
        status.visibility = GONE
        mic.imageTintList = ColorStateList.valueOf(theme.icon)
        val shown = this.words
        cells.forEachIndexed { i, cell ->
            cell.text = shown.getOrNull(i) ?: if (i == 0 && shown.isEmpty()) "start typing…" else ""
            cell.setTextColor(if (i == 0 && shown.isEmpty()) theme.textDim else theme.text)
            cell.typeface = if (i == 1) medium else regular
        }
        dividers.forEach { it.visibility = if (shown.size > 1) VISIBLE else INVISIBLE }
    }

    override fun setStatus(text: String?, clickable: Boolean) {
        if (text == null) {
            setSuggestions(words)
            return
        }
        listening = false
        statusClickable = clickable
        group.visibility = GONE
        status.visibility = VISIBLE
        status.text = text
        mic.imageTintList = ColorStateList.valueOf(theme.icon)
    }

    override fun setListening(on: Boolean) {
        listening = on
        mascot.setListening(on)
        if (!on) {
            setSuggestions(words)
            return
        }
        statusClickable = true
        group.visibility = GONE
        status.visibility = VISIBLE
        status.text = "Listening… tap to stop"
        status.setTextColor(theme.text)
        mic.imageTintList = ColorStateList.valueOf(theme.accent)
        mascot.setListening(true)
    }

    override fun setLanguage(language: String) {
        // The canvas space key reads this label; the bar keeps it for future use.
        contentDescription = language
    }

    override fun setMascotVisible(visible: Boolean) {
        mascot.visibility = if (visible) VISIBLE else GONE
        barIcon.visibility = if (visible) GONE else VISIBLE
    }

    override fun pokeMascot() {
        mascot.poke()
        mascot.setListening(listening)
    }

    override fun setBarVisible(visible: Boolean) {
        visibility = if (visible) VISIBLE else GONE
    }

    /** True when the bar should sit inside the keyboard panel instead of floating. */
    fun setDocked(value: Boolean) {
        if (docked == value) return
        docked = value
        applyTheme(theme)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

package com.remino.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The in-keyboard quick settings sheet. It is a real XML view: only this sheet
 * redraws when a switch changes, not the key bed underneath.
 */
class QuickSettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    interface Listener {
        fun onAppearance(mode: Appearance)
        fun onToggle(key: String, value: Boolean)
        fun onAction(action: ToolbarAction)
        fun onClose()
    }

    var listener: Listener? = null

    private lateinit var auto: TextView
    private lateinit var light: TextView
    private lateinit var dark: TextView
    private lateinit var haptics: Switch
    private lateinit var sound: Switch
    private lateinit var preview: Switch
    private lateinit var floating: Switch
    private lateinit var grid: GridLayout
    private lateinit var close: View

    private var binding = false
    private var theme = KeyboardTheme.Dark
    private var selected = Appearance.SYSTEM

    override fun onFinishInflate() {
        super.onFinishInflate()
        auto = findViewById(R.id.appearance_auto)
        light = findViewById(R.id.appearance_light)
        dark = findViewById(R.id.appearance_dark)
        haptics = findViewById(R.id.toggle_haptics)
        sound = findViewById(R.id.toggle_sound)
        preview = findViewById(R.id.toggle_preview)
        floating = findViewById(R.id.toggle_floating)
        grid = findViewById(R.id.action_grid)
        close = findViewById(R.id.quick_close)

        auto.setOnClickListener { choose(Appearance.SYSTEM) }
        light.setOnClickListener { choose(Appearance.LIGHT) }
        dark.setOnClickListener { choose(Appearance.DARK) }
        close.setOnClickListener { listener?.onClose() }

        haptics.setOnCheckedChangeListener { _, value ->
            if (!binding) listener?.onToggle("haptics", value)
        }
        sound.setOnCheckedChangeListener { _, value ->
            if (!binding) listener?.onToggle("sound", value)
        }
        preview.setOnCheckedChangeListener { _, value ->
            if (!binding) listener?.onToggle("preview", value)
        }
        floating.setOnCheckedChangeListener { _, value ->
            if (!binding) listener?.onToggle("floating", value)
        }

        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val tag = child.tag as? String ?: continue
            val action = runCatching { ToolbarAction.valueOf(tag) }.getOrNull() ?: continue
            child.setOnClickListener { listener?.onAction(action) }
        }
        applyTheme(theme)
    }

    fun bind(prefs: KeyboardPrefs) {
        binding = true
        haptics.isChecked = prefs.haptics
        sound.isChecked = prefs.sound
        preview.isChecked = prefs.keyPreview
        floating.isChecked = prefs.floatingKeyboard
        selected = prefs.appearance
        updateChips()
        binding = false
    }

    fun setTheme(theme: KeyboardTheme) {
        this.theme = theme
        applyTheme(theme)
    }

    private fun choose(mode: Appearance) {
        if (selected == mode) return
        selected = mode
        updateChips()
        listener?.onAppearance(mode)
    }

    private fun updateChips() {
        listOf(
            auto to Appearance.SYSTEM,
            light to Appearance.LIGHT,
            dark to Appearance.DARK,
        ).forEach { (view, mode) ->
            val on = selected == mode
            view.setTextColor(if (on) theme.onAccent else theme.text)
            view.background = keyBackground(if (on) theme.accent else theme.key)
        }
    }

    private fun applyTheme(theme: KeyboardTheme) {
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                dp(14f), dp(14f), dp(14f), dp(14f), 0f, 0f, 0f, 0f,
            )
            setColor(theme.base)
        }
        tintChildren(this, theme)
        listOf(haptics, sound, preview, floating).forEach { s ->
            s.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(theme.accent, theme.icon),
            )
            s.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(theme.accent, theme.divider),
            )
        }
        updateChips()
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child is TextView) child.background = keyBackground(theme.key)
        }
    }

    private fun tintChildren(view: View, theme: KeyboardTheme) {
        when (view) {
            is TextView -> view.setTextColor(theme.text)
            is Switch -> view.setTextColor(theme.text)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) tintChildren(view.getChildAt(i), theme)
        }
    }

    private fun keyBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(color)
        }

    fun show() {
        if (visibility == VISIBLE) return
        visibility = VISIBLE
        alpha = 0f
        translationY = dp(10f)
        animate().alpha(1f).translationY(0f).setDuration(140L).start()
    }

    fun hide() {
        if (visibility != VISIBLE) return
        animate().alpha(0f).translationY(dp(10f)).setDuration(110L)
            .withEndAction { visibility = GONE }.start()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

package com.remino.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * The in-keyboard action tray. It covers the whole keyboard (bar and keys), uses
 * a fixed two-column, three-row page like the reference keyboard, and pages any
 * extra actions instead of scrolling. Preferences live in the companion app.
 */
class OptionsTrayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    interface Listener {
        fun onAction(action: ToolbarAction)
        fun onClose()
    }

    private data class Tile(val label: String, val icon: Int, val action: ToolbarAction)

    private val tiles = listOf(
        Tile("Clipboard", R.drawable.ic_clipboard, ToolbarAction.CLIPBOARD),
        Tile("Emoji", R.drawable.ic_smile, ToolbarAction.EMOJI),
        Tile("Sticker", R.drawable.ic_sticker, ToolbarAction.STICKER),
        Tile("Undo", R.drawable.ic_undo, ToolbarAction.UNDO),
        Tile("Redo", R.drawable.ic_redo, ToolbarAction.REDO),
        Tile("Theme", R.drawable.ic_theme, ToolbarAction.THEME),
        Tile("Language", R.drawable.ic_globe, ToolbarAction.LANGUAGE),
        Tile("Floating keys", R.drawable.ic_floating, ToolbarAction.FLOATING),
        Tile("Delete word", R.drawable.ic_delete_word, ToolbarAction.DELETE_WORD),
        Tile("Settings", R.drawable.ic_sliders, ToolbarAction.SETTINGS),
        Tile("Hide", R.drawable.ic_close, ToolbarAction.HIDE),
    )

    private val perPage = 6
    private val pageCount get() = (tiles.size + perPage - 1) / perPage
    private var page = 0

    var listener: Listener? = null

    private lateinit var iconBox: View
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var close: ImageButton
    private lateinit var grid: GridLayout
    private lateinit var dots: LinearLayout
    private lateinit var prev: TextView
    private lateinit var next: TextView

    private var theme = KeyboardTheme.Dark
    private var mascotColor = MascotColor.LIME

    override fun onFinishInflate() {
        super.onFinishInflate()
        iconBox = findViewById(R.id.options_icon_box)
        icon = findViewById(R.id.options_icon)
        title = findViewById(R.id.options_title)
        subtitle = findViewById(R.id.options_subtitle)
        close = findViewById(R.id.options_close)
        grid = findViewById(R.id.action_grid)
        dots = findViewById(R.id.options_page_dots)
        prev = findViewById(R.id.options_prev)
        next = findViewById(R.id.options_next)

        close.setOnClickListener { listener?.onClose() }
        prev.setOnClickListener { turn(-1) }
        next.setOnClickListener { turn(1) }
        applyTheme(theme)
        render()
    }

    fun bind(prefs: KeyboardPrefs) {
        // Actions only; preferences stay in the companion app.
    }

    fun show(prefs: KeyboardPrefs, theme: KeyboardTheme, mascotColor: MascotColor) {
        val hostHeight = (parent as? View)?.height ?: 0
        (layoutParams as? FrameLayout.LayoutParams)?.let {
            it.height = if (hostHeight > 0) hostHeight else ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams = it
        }
        setTheme(theme, mascotColor)
        page = 0
        render()
        visibility = VISIBLE
        scrollTo(0, 0)
    }

    fun hide() {
        visibility = GONE
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    fun setTheme(theme: KeyboardTheme, mascotColor: MascotColor) {
        this.theme = theme
        this.mascotColor = mascotColor
        applyTheme(theme)
        render()
    }

    private fun turn(delta: Int) {
        val target = page + delta
        if (target !in 0 until pageCount) return
        page = target
        render()
    }

    private fun render() {
        if (!::grid.isInitialized) return
        grid.removeAllViews()
        val start = page * perPage
        val end = minOf(start + perPage, tiles.size)
        for (i in start until end) {
            val index = i - start
            grid.addView(tileView(tiles[i]), tileParams(index / 2, index % 2))
        }
        renderChrome()
    }

    private fun tileView(tile: Tile): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f).toInt(), 0, dp(10f).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f)
                setColor(theme.key)
            }
            setOnClickListener { listener?.onAction(tile.action) }
        }
        val image = ImageView(context).apply {
            setImageResource(tile.icon)
            imageTintList = ColorStateList.valueOf(theme.icon)
        }
        row.addView(image, LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt()))
        val text = TextView(context).apply {
            text = tile.label
            setTextColor(theme.text)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10f).toInt()
            },
        )
        return row
    }

    private fun tileParams(row: Int, col: Int): GridLayout.LayoutParams =
        GridLayout.LayoutParams(
            GridLayout.spec(row, 1f),
            GridLayout.spec(col, 1f),
        ).apply {
            width = 0
            height = 0
            setMargins(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
        }

    private fun renderChrome() {
        dots.removeAllViews()
        for (i in 0 until pageCount) {
            val dot = TextView(context).apply {
                text = "●"
                textSize = 9f
                setTextColor(if (i == page) theme.accent else theme.textDim)
                setPadding(dp(5f).toInt(), 0, dp(5f).toInt(), 0)
                setOnClickListener {
                    page = i
                    render()
                }
            }
            dots.addView(dot)
        }
        val hasPages = pageCount > 1
        prev.visibility = if (hasPages) View.VISIBLE else View.INVISIBLE
        next.visibility = if (hasPages) View.VISIBLE else View.INVISIBLE
        prev.alpha = if (page > 0) 1f else 0.3f
        next.alpha = if (page < pageCount - 1) 1f else 0.3f
        prev.setTextColor(theme.textDim)
        next.setTextColor(theme.textDim)
        subtitle.setTextColor(theme.textDim)
    }

    private fun applyTheme(theme: KeyboardTheme) {
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                dp(14f), dp(14f), dp(14f), dp(14f), 0f, 0f, 0f, 0f,
            )
            setColor(theme.base)
        }
        icon.imageTintList = ColorStateList.valueOf(mascotColor.body)
        iconBox.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(mascotColor.body, 42),
        )
        title.setTextColor(theme.text)
        subtitle.setTextColor(theme.textDim)
        close.imageTintList = ColorStateList.valueOf(theme.icon)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

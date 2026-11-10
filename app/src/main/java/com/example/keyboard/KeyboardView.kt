package com.example.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class ShiftState { OFF, ONE_SHOT, CAPS }

enum class ToolbarAction {
    MENU, CLOSE, MIC, EMOJI, CLIPBOARD, THEME, LANGUAGE, SETTINGS, BACK, CLIP_PAUSE, CLIP_CLEAR, CLIP_EDIT, COPY_PASTE,
    UNDO, REDO, STICKER, STICKER_STYLE, STICKER_FONT, STICKER_SEND,
    FLOATING, DELETE_WORD, HIDE, TRAY_PREV, TRAY_NEXT,
}

/**
 * The whole keyboard drawn on one canvas: a rounded top toolbar (suggestions and icons)
 * above the keys, the emoji sheet or the clipboard list. Faster than one view per key.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onSpace()
        fun onEnter()
        fun onSuggestion(word: String)
        fun onSuggestionLongPress(word: String) {}
        fun onEmoji(emoji: String)
        fun onPaste(text: String)
        fun onGlobe()
        fun onShift()
        fun onDeleteWord()
        fun onToolbarAction(action: ToolbarAction)
        fun onPasteImage(item: ClipItem)
        fun onStickerSend(text: String, palette: Int, style: Int): Boolean
        fun onMascotState(visible: Boolean, deleting: Boolean) {}
    }

    private enum class Panel { KEYS, EMOJI, CLIPBOARD }

    private enum class Capture { NONE, EMOJI_SEARCH, STICKER }

    private class Target(
        val rect: RectF,
        val key: Key? = null,
        val action: ToolbarAction? = null,
        val suggestionIndex: Int = -1,
        val tabIndex: Int = -1,
        val resultIndex: Int = -1,
        val tray: Boolean = false,
    )

    private class TrayTile(
        val label: String,
        val icon: Int,
        val action: ToolbarAction,
    )

    private class SheetItem(
        val y: Float,
        val height: Float,
        val header: String? = null,
        val emojis: List<String>? = null,
        val hint: String? = null,
    )

    private class ClipCard(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val item: ClipItem? = null,
        val hint: String? = null,
    )

    var listener: Listener? = null

    var theme: KeyboardTheme = KeyboardTheme.Dark
        set(value) {
            field = value
            invalidate()
        }

    var shiftState: ShiftState = ShiftState.OFF
        private set

    var showingSymbols: Boolean = false
        private set

    private var panel = Panel.KEYS
    private var trayOpen = false
    private var sensitive = false
    private var showNumberRow = false
    private var previewEnabled = false
    private var mascotEnabled = true
    private var placement = MascotPlacement.TOOLBAR
    private var mascotColor = MascotColor.LIME
    private var mascotOffsetX = 0
    private var mascotOffsetY = -3
    private var heightScale = 1f
    private var extraBottomPad = 0
    private var fieldEmpty = true
    private var caretX = -1f
    private var copiedChip: String? = null
    private var copiedAt = 0L
    private val prefs = KeyboardPrefs(context)
    private var suggestions: List<String> = emptyList()
    private var composingActive = false
    private var navInset = 0
    private var lastShiftTap = 0L

    // Emoji search and sticker maker: letters typed go here instead of the app
    private var captureMode = Capture.NONE
    private val captureText = StringBuilder()
    private var searchResults: List<String> = emptyList()
    private var stickerPalette = 0
    private var stickerStyle = 0

    // Floating window and at-cursor mascot
    var floating = false
        private set
    private var overlayMascot = false
    var dragListener: ((Float, Float) -> Unit)? = null
    var dragEndListener: (() -> Unit)? = null
    private var gripPointer = -1
    private var gripRawX = 0f
    private var gripRawY = 0f
    private var publishedVisible = false
    private var publishedDeleting = false
    private val thumbs = HashMap<Long, android.graphics.Bitmap>()

    // Mascot (pushes the cursor back while deleting)
    private var lastDeleteAt = 0L
    private var mascotProgress = 0f
    private var mascotTarget = 0f

    // Shared scrolling area (emoji sheet and clipboard list)
    private val contentRect = RectF()
    private var scrollY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var gridPointer = -1
    private var gridDownY = 0f
    private var gridLastY = 0f
    private var gridDownX = 0f
    private var gridLastX = 0f
    private var gridMoved = false

    // Emoji sheet
    private val emojiCols = 9
    private val recentsStore = EmojiRecents(context)
    private var emojiRecents: List<String> = emptyList()
    private val categoryCache = HashMap<Int, List<String>>()
    private var sheet: List<SheetItem> = emptyList()
    private var sectionStarts = FloatArray(0)
    private var sheetHeight = 0f
    private val tabIcons = intArrayOf(
        R.drawable.ic_search, R.drawable.ic_clock, R.drawable.ic_smile, R.drawable.ic_person, R.drawable.ic_paw,
        R.drawable.ic_cup, R.drawable.ic_ball, R.drawable.ic_car, R.drawable.ic_bulb, R.drawable.ic_heart,
    )

    // Clipboard list
    private val clipStore = ClipboardStore.get(context)
    private var clipCards: List<ClipCard> = emptyList()
    private var clipContentH = 0f
    private var clipScrollY = 0f
    private var clipEditMode = false

    // The action tray: a labelled two-column grid over the keys area.
    private val trayTiles = listOf(
        TrayTile("Emoji", R.drawable.ic_smile, ToolbarAction.EMOJI),
        TrayTile("Make sticker", R.drawable.ic_sticker, ToolbarAction.STICKER),
        TrayTile("Clipboard", R.drawable.ic_clipboard, ToolbarAction.CLIPBOARD),
        TrayTile("Undo", R.drawable.ic_undo, ToolbarAction.UNDO),
        TrayTile("Redo", R.drawable.ic_redo, ToolbarAction.REDO),
        TrayTile("Settings", R.drawable.ic_sliders, ToolbarAction.SETTINGS),
        TrayTile("Language", R.drawable.ic_globe, ToolbarAction.LANGUAGE),
        TrayTile("Floating", R.drawable.ic_floating, ToolbarAction.FLOATING),
        TrayTile("Delete word", R.drawable.ic_delete_word, ToolbarAction.DELETE_WORD),
        TrayTile("Hide", R.drawable.ic_close, ToolbarAction.HIDE),
    )
    private val trayPerPage = 6
    private var trayPage = 0
    private val trayPageCount: Int get() = (trayTiles.size + trayPerPage - 1) / trayPerPage

    private val abcKey = Key("ABC", KeyType.LETTERS)
    private val panelSpaceKey = Key("", KeyType.SPACE)
    private val panelBackspaceKey = Key("", KeyType.BACKSPACE)

    private val targets = ArrayList<Target>()
    private val pressedByPointer = HashMap<Int, Target>()
    private var backspacePointer = -1
    private var backspaceDownY = 0f
    private var wordDeleteFired = false
    private val iconCache = HashMap<Int, Drawable>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val tmpRect = RectF()
    private val tmpRectThumb = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val slopRect = RectF()

    private var longPressCandidate: Target? = null
    private var longPressFired: Target? = null
    private val longPressRunnable = Runnable {
        val tg = longPressCandidate
        if (tg != null && pressedByPointer.values.any { it === tg }) {
            longPressFired = tg
            Feedback.longPress(this, prefs)
            if (tg.suggestionIndex >= 0) {
                suggestions.getOrNull(tg.suggestionIndex)?.let { listener?.onSuggestionLongPress(it) }
            } else {
                handleAction(ToolbarAction.EMOJI)
            }
        }
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            fireBackspace()
            uiHandler.postDelayed(this, 45L)
        }
    }

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            val clamped = min(bottom, dp(64f).toInt())
            if (clamped != navInset) {
                navInset = clamped
                requestLayout()
            }
            insets
        }
    }

    // ---------- sizes ----------

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val toolbarH: Float get() = dp(48f)
    private val rowH: Float get() = dp(46f * heightScale)
    private val rowGap: Float get() = dp(4f)
    private val sideMargin: Float get() = dp(1.5f)
    private val keyGap: Float get() = dp(2f)
    private val topPad: Float get() = dp(6f)
    private val bottomPad: Float
        get() = if (floating) {
            dp(10f) + dp(extraBottomPad.toFloat())
        } else {
            max(dp(14f) + dp(extraBottomPad.toFloat()), navInset.toFloat())
        }

    /**
     * A transparent strip above the keyboard where the mascot can stand.
     * Only "Above the keys" uses it: "At cursor" draws Reo in the caret overlay and
     * the toolbar keeps its own lane, so neither should grow the keyboard.
     */
    private val mascotBandH: Float
        get() = if (mascotEnabled && placement == MascotPlacement.ABOVE) dp(34f) else 0f

    /** The grab bar at the top of the floating keyboard. */
    private val handleH: Float get() = if (floating) dp(20f) else 0f

    private fun topOffset(): Float = handleH + mascotBandH
    private fun toolbarCenterY(): Float = topOffset() + toolbarH / 2f

    private fun rowCount(): Int = Layouts.rowCount(showNumberRow)
    private fun keysTop(): Float = topOffset() + toolbarH + topPad
    private fun keysHeight(): Float = rowCount() * rowH + (rowCount() - 1) * rowGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (keysTop() + keysHeight() + bottomPad).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildTargets()
        if (panel == Panel.CLIPBOARD) reloadClipboard()
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacks(repeatRunnable)
        uiHandler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    // ---------- public state ----------

    fun setShift(state: ShiftState) {
        shiftState = state
        invalidate()
    }

    /** Shows the symbols page (or the letters page) and leaves any panel. */
    fun setSymbols(on: Boolean) {
        val changed = showingSymbols != on || panel != Panel.KEYS
        showingSymbols = on
        panel = Panel.KEYS
        if (changed) {
            rebuildTargets()
            invalidate()
        }
    }

    private var appearance = Appearance.SYSTEM

    private fun systemIsDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // "Auto" follows the phone without waiting for the next settings change.
        if (appearance == Appearance.SYSTEM) theme = appearance.theme(systemIsDark())
    }

    fun applySettings(options: KeyboardPrefs) {
        val oldScale = heightScale
        val oldPad = extraBottomPad
        val oldMascot = mascotEnabled
        val oldPlacement = placement
        val oldNumberRow = showNumberRow

        previewEnabled = options.keyPreview
        mascotEnabled = options.mascot
        mascotColor = options.mascotColor
        mascotOffsetX = options.mascotOffsetX
        mascotOffsetY = options.mascotOffsetY
        heightScale = options.keyboardHeightPercent / 100f
        extraBottomPad = options.keyboardPadding
        placement = options.mascotPlacement
        showNumberRow = options.showNumberRow
        appearance = options.appearance
        theme = appearance.theme(systemIsDark())

        val layoutChanged = oldPlacement != placement || oldNumberRow != showNumberRow ||
            oldScale != heightScale || oldPad != extraBottomPad || oldMascot != mascotEnabled
        if (layoutChanged) requestLayout()
        rebuildTargets()
        invalidate()
    }

    /** True while the field has no text; the mascot idles and waves. */
    fun setFieldEmpty(value: Boolean) {
        if (fieldEmpty == value) return
        fieldEmpty = value
        lastDeleteAt = if (value) lastDeleteAt else 0L
        invalidate()
    }

    /** Caret position from CursorAnchorInfo, used to line the mascot up above the keys. */
    fun setCaretX(x: Float) {
        caretX = x
        invalidate()
    }

    /** Floating mode draws the keyboard as a rounded card with a grab bar. */
    fun setFloating(on: Boolean) {
        if (floating == on) return
        floating = on
        requestLayout()
        rebuildTargets()
        invalidate()
    }

    /** True when the mascot is drawn by the at-cursor overlay window instead of the band. */
    fun setOverlayMascot(on: Boolean) {
        if (overlayMascot == on) return
        overlayMascot = on
        requestLayout()
        rebuildTargets()
        invalidate()
    }

    /** Shows the copied-text pill in the toolbar for a few seconds. */
    fun showCopiedChip(text: String) {
        copiedChip = text.replace('\n', ' ').take(140)
        copiedAt = SystemClock.uptimeMillis()
        rebuildTargets()
        invalidate()
        uiHandler.removeCallbacks(clearChipRunnable)
        uiHandler.postDelayed(clearChipRunnable, 10_000L)
    }

    /** Rebuilds the clipboard sheet if it is open (a new item may have arrived). */
    fun refreshClipboard() {
        if (panel == Panel.CLIPBOARD) {
            reloadClipboard()
            invalidate()
        }
    }

    private val clearChipRunnable = Runnable {
        copiedChip = null
        rebuildTargets()
        invalidate()
    }

    /** Hides the "COPIED / Paste" pill as soon as the user starts typing. */
    fun dismissCopiedChip() {
        if (copiedChip == null) return
        copiedChip = null
        uiHandler.removeCallbacks(clearChipRunnable)
        rebuildTargets()
        invalidate()
    }

    fun setSuggestions(list: List<String>) {
        suggestions = list
        invalidate()
    }

    /** True while the user is in the middle of a word, so chip 0 is the typed text. */
    fun setComposing(active: Boolean) {
        composingActive = active
    }

    fun setSensitive(value: Boolean) {
        sensitive = value
        invalidate()
    }

    fun closeTray() {
        if (!trayOpen) return
        trayOpen = false
        rebuildTargets()
        invalidate()
    }

    // ---------- layout of touch targets ----------

    private fun rebuildTargets() {
        targets.clear()
        val w = width.toFloat()
        if (w <= 0f) return
        val cy = toolbarCenterY()
        val leftCx = dp(28f)
        val leftRect = RectF(leftCx - dp(22f), cy - dp(22f), leftCx + dp(22f), cy + dp(22f))

        if (panel == Panel.CLIPBOARD) {
            targets.add(Target(leftRect, action = ToolbarAction.BACK))
            val pauseCx = w - dp(68f)
            val editCx = w - dp(28f)
            targets.add(
                Target(
                    RectF(pauseCx - dp(22f), cy - dp(22f), pauseCx + dp(22f), cy + dp(22f)),
                    action = ToolbarAction.CLIP_PAUSE,
                ),
            )
            targets.add(
                Target(
                    RectF(editCx - dp(22f), cy - dp(22f), editCx + dp(22f), cy + dp(22f)),
                    action = ToolbarAction.CLIP_EDIT,
                ),
            )
            val top = keysTop()
            contentRect.set(sideMargin, top, w - sideMargin, top + keysHeight())
            return
        }

        if (captureMode != Capture.NONE) {
            targets.add(Target(leftRect, action = ToolbarAction.BACK))
            if (captureMode == Capture.STICKER) {
                val styleCx = w - dp(104f)
                val fontCx = w - dp(64f)
                val sendCx = w - dp(24f)
                targets.add(
                    Target(
                        RectF(styleCx - dp(20f), cy - dp(20f), styleCx + dp(20f), cy + dp(20f)),
                        action = ToolbarAction.STICKER_STYLE,
                    ),
                )
                targets.add(
                    Target(
                        RectF(fontCx - dp(20f), cy - dp(20f), fontCx + dp(20f), cy + dp(20f)),
                        action = ToolbarAction.STICKER_FONT,
                    ),
                )
                targets.add(
                    Target(
                        RectF(sendCx - dp(20f), cy - dp(20f), sendCx + dp(20f), cy + dp(20f)),
                        action = ToolbarAction.STICKER_SEND,
                    ),
                )
            } else {
                val first = dp(56f) + dp(112f) + dp(6f)
                val count = max(0, ((w - dp(8f) - first) / dp(40f)).toInt())
                for (i in 0 until count) {
                    val l = first + i * dp(40f)
                    targets.add(Target(RectF(l, cy - dp(20f), l + dp(40f), cy + dp(20f)), resultIndex = i))
                }
            }
            buildKeyTargets(w)
            return
        }

        targets.add(Target(leftRect, action = if (trayOpen) ToolbarAction.CLOSE else ToolbarAction.MENU))

        if (trayOpen) {
            if (trayPage >= trayPageCount) trayPage = 0
            val actions = listOf(
                ToolbarAction.EMOJI,
                ToolbarAction.STICKER,
                ToolbarAction.CLIPBOARD,
                ToolbarAction.UNDO,
                ToolbarAction.REDO,
                ToolbarAction.SETTINGS,
            )
            val startX = dp(60f)
            val endX = w - dp(16f)
            val step = (endX - startX) / actions.size
            actions.forEachIndexed { i, a ->
                val cx = startX + step * (i + 0.5f)
                targets.add(Target(RectF(cx - step / 2f, topOffset(), cx + step / 2f, topOffset() + toolbarH), action = a))
            }
            addTrayTargets()
        } else {
            val micCx = w - dp(28f)
            targets.add(
                Target(
                    RectF(micCx - dp(22f), cy - dp(22f), micCx + dp(22f), cy + dp(22f)),
                    action = ToolbarAction.MIC,
                ),
            )
            val regionL = dp(56f)
            val regionR = w - dp(56f)
            if (copiedChip != null) {
                targets.add(
                    Target(
                        RectF(regionL, cy - dp(18f), regionR, cy + dp(18f)),
                        action = ToolbarAction.COPY_PASTE,
                    ),
                )
            } else {
                val gap = dp(6f)
                val chipW = (regionR - regionL - 2 * gap) / 3f
                for (i in 0 until 3) {
                    val l = regionL + i * (chipW + gap)
                    targets.add(Target(RectF(l, cy - dp(16f), l + chipW, cy + dp(16f)), suggestionIndex = i))
                }
            }
        }

        if (trayOpen) return
        if (panel == Panel.EMOJI) buildEmojiTargets(w) else buildKeyTargets(w)
    }

    private fun trayArea(): RectF {
        val top = keysTop() + dp(6f)
        val bottom = keysTop() + keysHeight() - dp(34f)
        return RectF(sideMargin + dp(6f), top, width - sideMargin - dp(6f), max(top + dp(80f), bottom))
    }

    private fun addTrayTargets() {
        if (trayPage >= trayPageCount) trayPage = 0
        val area = trayArea()
        val gap = dp(8f)
        val cols = 2
        val rows = 3
        val tileW = (area.width() - gap) / cols
        val tileH = (area.height() - gap * (rows - 1)) / rows
        val start = trayPage * trayPerPage
        val end = min(start + trayPerPage, trayTiles.size)
        for (i in start until end) {
            val idx = i - start
            val col = idx % cols
            val row = idx / cols
            val l = area.left + col * (tileW + gap)
            val t = area.top + row * (tileH + gap)
            targets.add(
                Target(
                    RectF(l, t, l + tileW, t + tileH),
                    action = trayTiles[i].action,
                    tray = true,
                ),
            )
        }
        if (trayPageCount > 1) {
            val dotCy = area.bottom + dp(14f)
            targets.add(
                Target(
                    RectF(width / 2f - dp(80f), dotCy - dp(16f), width / 2f - dp(4f), dotCy + dp(16f)),
                    action = ToolbarAction.TRAY_PREV,
                ),
            )
            targets.add(
                Target(
                    RectF(width / 2f + dp(4f), dotCy - dp(16f), width / 2f + dp(80f), dotCy + dp(16f)),
                    action = ToolbarAction.TRAY_NEXT,
                ),
            )
        }
    }

    private fun buildKeyTargets(w: Float) {
        val rows = if (showingSymbols) Layouts.symbols(showNumberRow) else Layouts.letters(showNumberRow)
        val avail = w - 2 * sideMargin
        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (avail - keyGap * (row.size - 1)) / total
            var x = sideMargin
            val top = keysTop() + r * (rowH + rowGap)
            for (key in row) {
                val kw = key.weight * unit
                if (key.type != KeyType.SPACER) {
                    targets.add(Target(RectF(x, top, x + kw, top + rowH), key = key))
                }
                x += kw + keyGap
            }
        }
    }

    private fun buildEmojiTargets(w: Float) {
        val left = sideMargin
        val right = w - sideMargin
        val top = keysTop()
        val tabsH = dp(38f)
        val tabs = EmojiData.categories.size + 2
        val tabW = (right - left) / tabs
        for (i in 0 until tabs) {
            targets.add(Target(RectF(left + i * tabW, top, left + (i + 1) * tabW, top + tabsH), tabIndex = i))
        }
        val bottomH = dp(46f)
        val bottomTop = top + keysHeight() - bottomH
        contentRect.set(left, top + tabsH + dp(2f), right, bottomTop - dp(2f))
        val bw = dp(66f)
        val stickerW = dp(46f)
        targets.add(Target(RectF(left, bottomTop, left + bw, bottomTop + bottomH), key = abcKey))
        // Quick ways into the sticker maker and settings, like the emoji tray's
        // bottom row.
        val stickerLeft = left + bw + keyGap
        targets.add(
            Target(
                RectF(stickerLeft, bottomTop, stickerLeft + stickerW, bottomTop + bottomH),
                action = ToolbarAction.STICKER,
            ),
        )
        val settingsLeft = stickerLeft + stickerW + keyGap
        targets.add(
            Target(
                RectF(settingsLeft, bottomTop, settingsLeft + stickerW, bottomTop + bottomH),
                action = ToolbarAction.SETTINGS,
            ),
        )
        val spaceLeft = settingsLeft + stickerW + keyGap
        targets.add(
            Target(RectF(spaceLeft, bottomTop, right - bw - keyGap, bottomTop + bottomH), key = panelSpaceKey),
        )
        targets.add(Target(RectF(right - bw, bottomTop, right, bottomTop + bottomH), key = panelBackspaceKey))
        buildSheet()
    }

    // ---------- emoji sheet ----------

    private fun openEmoji() {
        panel = Panel.EMOJI
        emojiRecents = recentsStore.load()
        scrollY = 0f
        rebuildTargets()
        invalidate()
    }

    private fun sectionName(i: Int): String = if (i == 0) "Recent" else EmojiData.categories[i - 1].name

    private fun emojisForSection(i: Int): List<String> {
        if (i == 0) return emojiRecents.filter { glyphPaint.hasGlyph(it) }
        return categoryCache.getOrPut(i) {
            EmojiData.categories[i - 1].emojis.filter { glyphPaint.hasGlyph(it) }
        }
    }

    /** One long sheet: Recent first, then Smileys, People and so on, each with a heading. */
    private fun buildSheet() {
        val sections = EmojiData.categories.size + 1
        val cell = contentRect.width() / emojiCols
        val headerH = dp(30f)
        val items = ArrayList<SheetItem>()
        val starts = FloatArray(sections)
        var y = 0f
        for (s in 0 until sections) {
            starts[s] = y
            items.add(SheetItem(y, headerH, header = sectionName(s)))
            y += headerH
            val list = emojisForSection(s)
            if (list.isEmpty()) {
                items.add(SheetItem(y, dp(40f), hint = "Emoji you use will appear here"))
                y += dp(40f)
            } else {
                var i = 0
                while (i < list.size) {
                    items.add(SheetItem(y, cell, emojis = list.subList(i, min(i + emojiCols, list.size))))
                    y += cell
                    i += emojiCols
                }
            }
        }
        sheet = items
        sectionStarts = starts
        sheetHeight = y
    }

    private fun currentSection(): Int {
        var current = 0
        for (i in sectionStarts.indices) {
            if (sectionStarts[i] <= scrollY + dp(8f)) current = i
        }
        return current
    }

    private fun tapEmoji(x: Float, y: Float) {
        if (!contentRect.contains(x, y)) return
        val local = y - contentRect.top + scrollY
        val row = sheet.firstOrNull { local >= it.y && local < it.y + it.height } ?: return
        val list = row.emojis ?: return
        val cell = contentRect.width() / emojiCols
        val col = ((x - contentRect.left) / cell).toInt()
        if (col < 0 || col >= list.size) return
        val emoji = list[col]
        Feedback.tap(this, prefs)
        recentsStore.add(emoji)
        listener?.onEmoji(emoji)
    }

    // ---------- clipboard list ----------

    private fun openClipboard() {
        // Clipboard history stays out of password and private fields.
        if (sensitive) return
        panel = Panel.CLIPBOARD
        scrollY = 0f
        clipScrollY = 0f
        clipEditMode = false
        rebuildTargets()
        reloadClipboard()
        invalidate()
    }

    private fun reloadClipboard() {
        if (contentRect.width() <= 0f || contentRect.height() <= 0f) {
            clipCards = emptyList()
            clipContentH = 0f
            return
        }
        val items = clipStore.items().sortedByDescending { it.pinned }
        val cards = ArrayList<ClipCard>()
        val pad = dp(2f)
        val gap = dp(8f)
        val topPad = dp(6f)
        val availW = (contentRect.width() - pad * 2f).coerceAtLeast(dp(80f))
        val colW = (availW - gap) / 2f
        // Four rows visible, like the reference, and a little taller on big screens.
        val cardH = ((contentRect.height() - topPad - gap * 3f) / 4.15f)
            .coerceIn(dp(52f), dp(78f))

        if (items.isEmpty()) {
            val msg = if (clipStore.paused) "Clipboard history is paused" else "Copied text appears here"
            cards.add(ClipCard(pad, topPad, availW, cardH, hint = msg))
            clipContentH = topPad + cardH
        } else {
            // Column-major: the left column fills first, the right column is staggered.
            val leftCount = (items.size + 1) / 2
            var y = topPad
            for (i in 0 until leftCount) {
                cards.add(ClipCard(pad, y, colW, cardH, item = items[i]))
                y += cardH + gap
            }
            val leftH = if (leftCount > 0) y - gap else 0f

            var ry = topPad + (cardH + gap) / 3f
            for (i in leftCount until items.size) {
                cards.add(ClipCard(pad + colW + gap, ry, colW, cardH, item = items[i]))
                ry += cardH + gap
            }
            val rightH = if (items.size > leftCount) ry - gap else 0f
            clipContentH = max(leftH, rightH)
        }
        clipCards = cards
        clipScrollY = clipScrollY.coerceIn(0f, max(0f, clipContentH + dp(4f) - contentRect.height()))
    }

    private fun maxClipScroll(): Float = max(0f, clipContentH + dp(4f) - contentRect.height())

    /** "just now", "12m ago", "3h ago", "2d ago". */
    private fun relativeTime(time: Long): String {
        val diff = max(0L, System.currentTimeMillis() - time)
        return when {
            diff < 60_000L -> "just now"
            diff < 3_600_000L -> "${diff / 60_000L}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
            else -> "${diff / 86_400_000L}d ago"
        }
    }

    private fun thumbnailFor(item: ClipItem): android.graphics.Bitmap? {
        thumbs[item.time]?.let { return it }
        val bmp = try {
            val bytes = android.util.Base64.decode(item.image, android.util.Base64.NO_WRAP)
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
        if (bmp != null) {
            if (thumbs.size > 24) thumbs.clear()
            thumbs[item.time] = bmp
        }
        return bmp
    }

    private fun tapClip(x: Float, y: Float) {
        if (!contentRect.contains(x, y)) return
        val localX = x - contentRect.left
        val localY = y - contentRect.top + clipScrollY
        val card = clipCards.firstOrNull {
            localX >= it.left && localX < it.left + it.width &&
                localY >= it.top && localY < it.top + it.height
        } ?: return
        val item = card.item ?: return
        Feedback.tap(this, prefs)
        if (clipEditMode) {
            val rx = localX - card.left
            val fromTop = localY - card.top
            val fromBottom = card.top + card.height - localY
            when {
                rx > card.width - dp(34f) && fromTop < dp(34f) -> {
                    clipStore.remove(item)
                    reloadClipboard()
                }
                rx > card.width - dp(34f) && fromBottom < dp(34f) -> {
                    clipStore.togglePin(item)
                    reloadClipboard()
                }
            }
        } else if (item.image == null) {
            listener?.onPaste(item.text)
        } else {
            listener?.onPasteImage(item)
        }
        invalidate()
    }

    // ---------- mascot ----------

    private fun fireBackspace() {
        if (captureMode != Capture.NONE) {
            if (captureText.isNotEmpty()) captureText.setLength(captureText.length - 1)
            onCaptureChanged()
            return
        }
        listener?.onBackspace()
        noteDelete()
    }

    private val mascotStateRunnable = Runnable {
        publishMascotState()
        invalidate()
    }

    /** Tells the service when the mascot should appear, so the at-cursor overlay can follow. */
    private fun publishMascotState() {
        val visible = mascotVisible()
        val deleting = deletingRecently()
        if (visible == publishedVisible && deleting == publishedDeleting) return
        publishedVisible = visible
        publishedDeleting = deleting
        listener?.onMascotState(visible, deleting)
    }

    private fun noteDelete() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDeleteAt > 2500L) {
            mascotProgress = 0f
            mascotTarget = 0f
        }
        lastDeleteAt = now
        mascotTarget += 0.06f
        if (mascotTarget > 1f) {
            mascotTarget = 0f
            mascotProgress = 0f
        }
        uiHandler.removeCallbacks(mascotStateRunnable)
        uiHandler.postDelayed(mascotStateRunnable, 950L)
        postInvalidateOnAnimation()
    }

    private fun deletingRecently(): Boolean =
        SystemClock.uptimeMillis() - lastDeleteAt < 900L

    /**
     * The mascot only comes out when the field is empty (idle, waving) or while
     * the user is deleting. It stays hidden during normal typing.
     */
    private fun mascotVisible(): Boolean =
        mascotEnabled && !trayOpen && panel == Panel.KEYS && captureMode == Capture.NONE &&
            copiedChip == null && (fieldEmpty || deletingRecently())

    private fun laneVisible(): Boolean =
        mascotVisible() && placement == MascotPlacement.TOOLBAR

    private fun drawMascotLane(canvas: Canvas, t: KeyboardTheme) {
        val left = dp(56f)
        val right = width - dp(56f)
        val cy = toolbarCenterY()
        tmpRect.set(left, cy - dp(16f), right, cy + dp(16f))
        fill.color = t.key
        canvas.drawRoundRect(tmpRect, dp(16f), dp(16f), fill)

        mascotProgress += (mascotTarget - mascotProgress) * 0.25f
        val travelLeft = left + dp(24f)
        val travelRight = right - dp(46f)
        val caretX = travelRight - (travelRight - travelLeft) * mascotProgress
        val now = SystemClock.uptimeMillis()
        val moving = abs(mascotTarget - mascotProgress) > 0.004f

        // The blinking cursor being pushed back
        if (moving || (now / 450L) % 2L == 0L) {
            fill.color = t.icon
            tmpRect.set(caretX - dp(1.5f), cy - dp(10f), caretX + dp(1.5f), cy + dp(10f))
            canvas.drawRoundRect(tmpRect, dp(1.5f), dp(1.5f), fill)
        }

        // Reo, shoving the blinking caret back.
        val bx = caretX + dp(23f) + dp(mascotOffsetX.toFloat())
        val idle = !moving && fieldEmpty
        val press = moving || deletingRecently()
        val walk = if (press) sin(now / 70.0).toFloat() * dp(2f) else 0f
        MascotArt.draw(
            canvas = canvas,
            fill = fill,
            stroke = strokePaint,
            tmp = tmpRect,
            cx = bx,
            cy = cy + dp(mascotOffsetY.toFloat()),
            r = dp(11f),
            body = mascotColor.body,
            onBody = mascotColor.ink,
            now = now,
            wave = idle,
            pushToX = if (idle) null else caretX + dp(4f),
            deleting = press,
            walk = walk,
        )
    }

    // ---------- emoji search and sticker maker ----------

    private fun startCapture(mode: Capture) {
        captureMode = mode
        captureText.setLength(0)
        searchResults = emptyList()
        panel = Panel.KEYS
        rebuildTargets()
        invalidate()
    }

    private fun exitCapture() {
        captureMode = Capture.NONE
        captureText.setLength(0)
        searchResults = emptyList()
        rebuildTargets()
        invalidate()
    }

    private fun onCaptureChanged() {
        if (captureMode == Capture.EMOJI_SEARCH) {
            searchResults = EmojiData.search(captureText.toString()).filter { glyphPaint.hasGlyph(it) }
        }
        invalidate()
    }

    private fun captureAppend(text: String) {
        captureText.append(text)
        onCaptureChanged()
    }

    private fun sendSticker() {
        val text = captureText.toString().trim()
        if (text.isEmpty()) return
        if (listener?.onStickerSend(text, stickerPalette, stickerStyle) == true) exitCapture()
    }

    private fun drawCapturePill(canvas: Canvas, t: KeyboardTheme, w: Float) {
        val cy = toolbarCenterY()
        val left = dp(56f)
        val right = if (captureMode == Capture.STICKER) w - dp(132f) else left + dp(112f)
        tmpRect.set(left, cy - dp(16f), right, cy + dp(16f))
        fill.color = t.key
        canvas.drawRoundRect(tmpRect, dp(16f), dp(16f), fill)
        val empty = captureText.isEmpty()
        val hint = if (captureMode == Capture.STICKER) "Type sticker text" else "Search emoji"
        textPaint.textSize = sp(14f)
        textPaint.color = if (empty) t.textDim else t.text
        textPaint.textAlign = Paint.Align.LEFT
        val shown = TextUtils.ellipsize(
            if (empty) hint else captureText.toString(), textPaint, tmpRect.width() - dp(24f), TextUtils.TruncateAt.START,
        )
        val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(shown, 0, shown.length, left + dp(12f), y, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawResult(canvas: Canvas, tg: Target, t: KeyboardTheme) {
        val e = searchResults.getOrNull(tg.resultIndex) ?: return
        textPaint.textSize = sp(24f)
        textPaint.color = t.text
        val y = tg.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(e, tg.rect.centerX(), y, textPaint)
    }

    // ---------- hit testing ----------

    private fun hit(x: Float, y: Float): Target? {
        for (tg in targets) {
            if (tg.rect.contains(x, y)) return tg
        }
        if (y >= topOffset() + toolbarH) {
            var best: Target? = null
            var bestD = Float.MAX_VALUE
            for (tg in targets) {
                if (tg.key == null) continue
                val dx = x - tg.rect.centerX()
                val dy = y - tg.rect.centerY()
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = tg
                }
            }
            val limit = dp(40f)
            if (best != null && bestD < limit * limit) return best
        }
        return null
    }

    private fun isPressed(tg: Target): Boolean = pressedByPointer.values.any { it === tg }

    private fun labelFor(key: Key): String =
        if (!showingSymbols && shiftState != ShiftState.OFF) key.label.uppercase() else key.label

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(t.toolbarRadiusDp)
        val lane = laneVisible()

        publishMascotState()
        if (floating) {
            tmpRect.set(0f, 0f, w, h)
            fill.color = t.base
            canvas.drawRoundRect(tmpRect, r, r, fill)
            fill.color = t.toolbar
            tmpRect.set(dp(4f), handleH + mascotBandH, w - dp(4f), handleH + mascotBandH + toolbarH)
            canvas.drawRoundRect(tmpRect, dp(20f), dp(20f), fill)
            fill.color = t.textDim
            tmpRect.set(w / 2f - dp(22f), dp(7f), w / 2f + dp(22f), dp(11f))
            canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), fill)
        } else {
            drawTopRounded(canvas, h, w, r, t.base, topOffset())
            drawTopRounded(canvas, topOffset() + toolbarH, w, r, t.toolbar, topOffset())
        }

        if (panel == Panel.CLIPBOARD) {
            drawLeftLabel(canvas, "Clipboard", dp(56f), toolbarCenterY(), sp(16f), t.text)
        } else if (sensitive && !trayOpen && captureMode == Capture.NONE) {
            drawLabel(canvas, "Private field", w / 2f, toolbarCenterY(), sp(14f), t.textDim)
        }

        if (captureMode != Capture.NONE) drawCapturePill(canvas, t, w)

        if (copiedChip != null && !trayOpen && captureMode == Capture.NONE) drawCopiedChip(canvas, t, w)

        for (tg in targets) {
            val key = tg.key
            val action = tg.action
            when {
                tg.suggestionIndex >= 0 -> if (!lane) drawChip(canvas, tg, t)
                tg.resultIndex >= 0 -> drawResult(canvas, tg, t)
                tg.tabIndex >= 0 -> drawTab(canvas, tg, t)
                action != null -> if (action != ToolbarAction.COPY_PASTE && !tg.tray) {
                    drawToolbarButton(canvas, tg, action, t)
                }
                key != null -> drawKey(canvas, tg, key, t)
            }
        }

        when (panel) {
            Panel.EMOJI -> drawEmojiSheet(canvas, t)
            Panel.CLIPBOARD -> drawClipboardList(canvas, t)
            Panel.KEYS -> drawPreviews(canvas, t)
        }

        if (trayOpen) drawTrayGrid(canvas, t)

        if (lane) {
            drawMascotLane(canvas, t)
            postInvalidateOnAnimation()
        } else if (mascotEnabled && placement == MascotPlacement.ABOVE && mascotVisible()) {
            drawBandMascot(canvas, t)
            postInvalidateOnAnimation()
        }
    }

    private fun drawTopRounded(canvas: Canvas, bottom: Float, w: Float, r: Float, color: Int, top: Float = 0f) {
        if (bottom <= top) return
        fill.color = color
        path.reset()
        tmpRect.set(0f, top, w, bottom)
        val radii = if (top > 0f) {
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        path.addRoundRect(tmpRect, radii, Path.Direction.CW)
        canvas.drawPath(path, fill)
    }

    /** The copied-text pill that briefly replaces the suggestion row. */
    private fun drawCopiedChip(canvas: Canvas, t: KeyboardTheme, w: Float) {
        val cy = toolbarCenterY()
        val left = dp(56f)
        val right = w - dp(56f)
        tmpRect.set(left, cy - dp(18f), right, cy + dp(18f))
        fill.color = t.card
        canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), fill)
        strokePaint.color = ColorUtils.setAlphaComponent(t.accent, 90)
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), strokePaint)

        drawIcon(canvas, R.drawable.ic_clipboard, left + dp(20f), cy, dp(18f), t.accent)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = sp(8f)
        textPaint.color = t.accent
        val labelY = cy - dp(1f)
        canvas.drawText("COPIED", left + dp(36f), labelY, textPaint)
        textPaint.textSize = sp(12f)
        textPaint.color = t.text
        val maxW = right - left - dp(110f)
        val snippet = TextUtils.ellipsize(
            copiedChip ?: "", textPaint, maxW, TextUtils.TruncateAt.END,
        )
        canvas.drawText(snippet, 0, snippet.length, left + dp(36f), cy + dp(12f), textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = t.onAccent
        textPaint.textSize = sp(11f)
        tmpRect.set(right - dp(62f), cy - dp(13f), right - dp(8f), cy + dp(13f))
        fill.color = t.accent
        canvas.drawRoundRect(tmpRect, dp(13f), dp(13f), fill)
        canvas.drawText("Paste", tmpRect.centerX(), cy + dp(3.5f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawBandMascot(canvas: Canvas, t: KeyboardTheme) {
        val now = SystemClock.uptimeMillis()
        val cx = (if (caretX > 0f) caretX.coerceIn(dp(30f), width - dp(30f)) else width / 2f) +
            dp(mascotOffsetX.toFloat())
        val deleting = deletingRecently()
        val r = dp(10f) * if (deleting) 1.14f else 1f
        val cy = handleH + mascotBandH / 2f + dp(mascotOffsetY.toFloat())
        MascotArt.draw(
            canvas = canvas,
            fill = fill,
            stroke = strokePaint,
            tmp = tmpRect,
            cx = cx,
            cy = cy,
            r = r,
            body = mascotColor.body,
            onBody = mascotColor.ink,
            now = now,
            wave = !deleting,
            pushToX = null,
            deleting = deleting,
            walk = if (deleting) sin(now / 70.0).toFloat() * dp(2f) else 0f,
        )
        // The caret it is standing next to.
        if ((now / 450L) % 2L == 0L || deleting) {
            fill.color = mascotColor.body
            tmpRect.set(cx + r + dp(5f), cy - dp(8f), cx + r + dp(7f), cy + dp(8f))
            canvas.drawRoundRect(tmpRect, dp(1f), dp(1f), fill)
        }
    }

    private fun drawToolbarButton(canvas: Canvas, tg: Target, action: ToolbarAction, t: KeyboardTheme) {
        val cx = tg.rect.centerX()
        val cy = tg.rect.centerY()
        if (action == ToolbarAction.STICKER_STYLE) {
            val colors = StickerMaker.palettes[stickerPalette]
            fill.color = colors.first
            canvas.drawCircle(cx, cy, dp(13f), fill)
            strokePaint.color = t.icon
            strokePaint.strokeWidth = dp(1.5f)
            canvas.drawCircle(cx, cy, dp(16f), strokePaint)
            textPaint.textSize = sp(11f)
            textPaint.color = colors.second
            textPaint.typeface = Typeface.DEFAULT_BOLD
            val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("Aa", cx, y, textPaint)
            textPaint.typeface = Typeface.DEFAULT
            return
        }
        if (action == ToolbarAction.STICKER_FONT) {
            val style = StickerMaker.styles[stickerStyle]
            fill.color = if (isPressed(tg)) t.specialKey else t.key
            canvas.drawCircle(cx, cy, dp(16f), fill)
            textPaint.textSize = sp(14f)
            textPaint.color = t.text
            textPaint.typeface = style.typeface
            val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("Aa", cx, y, textPaint)
            textPaint.typeface = Typeface.DEFAULT
            return
        }
        if (action == ToolbarAction.STICKER_SEND) {
            fill.color = t.accent
            canvas.drawCircle(cx, cy, dp(16f), fill)
            drawIcon(canvas, R.drawable.ic_send, cx, cy, dp(22f), t.onAccent)
            return
        }
        val circular = action == ToolbarAction.MENU ||
            action == ToolbarAction.CLOSE ||
            action == ToolbarAction.BACK ||
            action == ToolbarAction.CLIP_PAUSE ||
            action == ToolbarAction.CLIP_EDIT ||
            (action == ToolbarAction.MIC && !trayOpen)
        val pressed = isPressed(tg)
        if (circular || pressed) {
            fill.color = if (pressed) ColorUtils.blendARGB(t.key, Color.WHITE, 0.18f) else t.key
            canvas.drawCircle(cx, cy, dp(16f), fill)
        }
        val res = when (action) {
            ToolbarAction.MENU -> R.drawable.ic_grid
            ToolbarAction.CLOSE -> R.drawable.ic_close
            ToolbarAction.MIC -> R.drawable.ic_mic
            ToolbarAction.EMOJI -> R.drawable.ic_smile
            ToolbarAction.CLIPBOARD -> R.drawable.ic_clipboard
            ToolbarAction.THEME -> R.drawable.ic_theme
            ToolbarAction.LANGUAGE -> R.drawable.ic_globe
            ToolbarAction.SETTINGS -> R.drawable.ic_sliders
            ToolbarAction.BACK -> R.drawable.ic_back
            ToolbarAction.CLIP_PAUSE -> if (clipStore.paused) R.drawable.ic_play else R.drawable.ic_pause
            ToolbarAction.CLIP_CLEAR -> R.drawable.ic_trash
            ToolbarAction.CLIP_EDIT -> if (clipEditMode) R.drawable.ic_trash else R.drawable.ic_edit
            ToolbarAction.UNDO -> R.drawable.ic_undo
            ToolbarAction.REDO -> R.drawable.ic_redo
            ToolbarAction.COPY_PASTE -> R.drawable.ic_clipboard
            ToolbarAction.STICKER -> R.drawable.ic_sticker
            ToolbarAction.STICKER_STYLE -> R.drawable.ic_theme
            ToolbarAction.STICKER_FONT -> R.drawable.ic_text
            ToolbarAction.STICKER_SEND -> R.drawable.ic_send
            ToolbarAction.FLOATING -> R.drawable.ic_floating
            ToolbarAction.DELETE_WORD -> R.drawable.ic_delete_word
            ToolbarAction.HIDE -> R.drawable.ic_close
            ToolbarAction.TRAY_PREV, ToolbarAction.TRAY_NEXT -> R.drawable.ic_back
        }
        drawIcon(canvas, res, cx, cy, dp(24f), t.icon)
    }

    private fun drawChip(canvas: Canvas, tg: Target, t: KeyboardTheme) {
        val label = suggestions.getOrNull(tg.suggestionIndex) ?: return
        val highlighted = tg.suggestionIndex == 1
        val base = if (highlighted) t.inverted else t.key
        fill.color = if (isPressed(tg)) ColorUtils.blendARGB(base, Color.GRAY, 0.2f) else base
        val r = tg.rect
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        textPaint.textSize = sp(15f)
        textPaint.color = if (highlighted) t.onInverted else t.text
        val shown = TextUtils.ellipsize(label, textPaint, r.width() - dp(16f), TextUtils.TruncateAt.END)
        val y = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(shown, 0, shown.length, r.centerX(), y, textPaint)
    }

    private fun drawTab(canvas: Canvas, tg: Target, t: KeyboardTheme) {
        val r = tg.rect
        val selected = tg.tabIndex >= 1 && tg.tabIndex - 1 == currentSection()
        if (selected || isPressed(tg)) {
            val inset = dp(2f)
            tmpRect.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            fill.color = t.key
            canvas.drawRoundRect(tmpRect, tmpRect.height() / 2f, tmpRect.height() / 2f, fill)
        }
        drawIcon(canvas, tabIcons[tg.tabIndex], r.centerX(), r.centerY(), dp(22f), if (selected) t.text else t.textDim)
    }

    private fun drawEmojiSheet(canvas: Canvas, t: KeyboardTheme) {
        val maxScroll = max(0f, sheetHeight - contentRect.height())
        scrollY = scrollY.coerceIn(0f, maxScroll)
        val cell = contentRect.width() / emojiCols

        canvas.save()
        canvas.clipRect(contentRect)
        for (item in sheet) {
            val top = contentRect.top + item.y - scrollY
            if (top + item.height < contentRect.top || top > contentRect.bottom) continue
            val header = item.header
            val emojis = item.emojis
            val hint = item.hint
            if (header != null) {
                drawLeftLabel(canvas, header, contentRect.left + dp(6f), top + item.height / 2f, sp(12f), t.textDim)
            } else if (emojis != null) {
                textPaint.textSize = sp(27f)
                textPaint.color = t.text
                for (col in emojis.indices) {
                    val cx = contentRect.left + col * cell + cell / 2f
                    val y = top + cell / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(emojis[col], cx, y, textPaint)
                }
            } else if (hint != null) {
                drawLabel(canvas, hint, contentRect.centerX(), top + item.height / 2f, sp(13f), t.textDim)
            }
        }
        canvas.restore()
    }

    private fun drawTrayGrid(canvas: Canvas, t: KeyboardTheme) {
        // A scrim so the keys underneath never bleed through.
        fill.color = ColorUtils.blendARGB(t.base, Color.BLACK, 0.12f)
        tmpRect.set(0f, keysTop(), width.toFloat(), height.toFloat())
        canvas.drawRect(tmpRect, fill)

        for (tg in targets) {
            if (!tg.tray) continue
            val tile = trayTiles.firstOrNull { it.action == tg.action } ?: continue
            val r = tg.rect
            fill.color = if (isPressed(tg)) {
                ColorUtils.blendARGB(t.card, Color.WHITE, 0.10f)
            } else {
                t.card
            }
            canvas.drawRoundRect(r, dp(22f), dp(22f), fill)

            drawIcon(canvas, tile.icon, r.left + dp(26f), r.centerY(), dp(22f), t.icon)

            textPaint.textSize = sp(14f)
            textPaint.color = t.text
            textPaint.textAlign = Paint.Align.LEFT
            val labelLeft = r.left + dp(48f)
            val labelRight = r.right - dp(26f)
            val label = TextUtils.ellipsize(
                tile.label, textPaint,
                (labelRight - labelLeft).coerceAtLeast(dp(8f)),
                TextUtils.TruncateAt.END,
            )
            val baseline = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, 0, label.length, labelLeft, baseline, textPaint)

            textPaint.color = t.textDim
            textPaint.textSize = sp(17f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("›", r.right - dp(16f), baseline, textPaint)
        }

        if (trayPageCount > 1) {
            val area = trayArea()
            val dotY = area.bottom + dp(14f)
            val dotR = dp(3f)
            val step = dp(14f)
            val total = (trayPageCount - 1) * step
            var x = width / 2f - total / 2f
            for (i in 0 until trayPageCount) {
                fill.color = if (i == trayPage) t.accent else t.textDim
                canvas.drawCircle(x, dotY, dotR, fill)
                x += step
            }
        }
    }

    private fun drawClipboardList(canvas: Canvas, t: KeyboardTheme) {
        clipScrollY = clipScrollY.coerceIn(0f, maxClipScroll())
        canvas.save()
        canvas.clipRect(contentRect)

        for (card in clipCards) {
            val left = contentRect.left + card.left
            val top = contentRect.top + card.top - clipScrollY
            if (top + card.height < contentRect.top - dp(4f) || top > contentRect.bottom + dp(4f)) continue
            val item = card.item
            val hint = card.hint
            if (item == null) {
                if (hint != null) {
                    drawLabel(canvas, hint, contentRect.centerX(), top + card.height / 2f, sp(13f), t.textDim)
                }
                continue
            }

            tmpRect.set(left, top, left + card.width, top + card.height)
            fill.color = t.card
            canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), fill)
            if (item.pinned) {
                clipStroke.color = t.accent
                clipStroke.strokeWidth = dp(1f)
                tmpRect.inset(dp(1f), dp(1f))
                canvas.drawRoundRect(tmpRect, dp(13f), dp(13f), clipStroke)
                tmpRect.inset(-dp(1f), -dp(1f))
            }

            var textLeft = tmpRect.left + dp(11f)
            val textRight = tmpRect.right - (if (clipEditMode) dp(34f) else dp(11f))
            if (item.image != null) {
                val thumb = thumbnailFor(item)
                if (thumb != null) {
                    val side = dp(30f)
                    tmpRectThumb.set(
                        textLeft, tmpRect.centerY() - side / 2f,
                        textLeft + side, tmpRect.centerY() + side / 2f,
                    )
                    canvas.save()
                    path.reset()
                    path.addRoundRect(tmpRectThumb, dp(7f), dp(7f), Path.Direction.CW)
                    canvas.clipPath(path)
                    canvas.drawBitmap(thumb, null as android.graphics.Rect?, tmpRectThumb, imagePaint)
                    canvas.restore()
                    textLeft = tmpRectThumb.right + dp(8f)
                }
            }

            textPaint.textSize = sp(12.5f)
            textPaint.color = t.text
            textPaint.textAlign = Paint.Align.LEFT
            val visible = if (item.image != null) "Image" else item.text.replace("\n", " ")
            val maxW = (textRight - textLeft).coerceAtLeast(dp(16f))
            val lines = wrapTwoLines(visible, maxW)
            val lineH = textPaint.descent() - textPaint.ascent()
            var baseline = tmpRect.centerY() - (lineH * lines.size) / 2f - textPaint.ascent()
            for (line in lines) {
                canvas.drawText(line, textLeft, baseline, textPaint)
                baseline += lineH
            }

            if (clipEditMode) {
                val iconCx = tmpRect.right - dp(16f)
                drawIcon(canvas, R.drawable.ic_close, iconCx, tmpRect.top + dp(16f), dp(14f), t.textDim)
                val pinColor = if (item.pinned) t.accent else t.textDim
                drawIcon(
                    canvas,
                    if (item.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,
                    iconCx, tmpRect.bottom - dp(16f), dp(14f), pinColor,
                )
            }
        }
        canvas.restore()
    }
    /** Breaks text into at most two ellipsised lines that fit [maxWidth]. */
    private fun wrapTwoLines(text: String, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val first = TextUtils.ellipsize(text, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        if (textPaint.measureText(text) <= maxWidth) return listOf(first)
        // Find where the first line actually ends, then show the rest on line two.
        var end = first.length
        var consumed = 0
        while (consumed < text.length) {
            val next = text.indexOf(' ', consumed).let { if (it < 0) text.length else it }
            if (textPaint.measureText(text.substring(0, next)) > maxWidth) break
            consumed = next + 1
            if (consumed >= text.length) return listOf(first)
        }
        var restStart = consumed.coerceAtMost(text.length)
        if (restStart >= end) restStart = (end - 1).coerceAtLeast(0)
        val rest = text.substring(restStart).trimStart()
        if (rest.isEmpty()) return listOf(first)
        return listOf(first, TextUtils.ellipsize(rest, textPaint, maxWidth, TextUtils.TruncateAt.END).toString())
    }

    private fun drawKey(canvas: Canvas, tg: Target, key: Key, t: KeyboardTheme) {
        val r = tg.rect
        val pressed = isPressed(tg)
        val cx = r.centerX()
        val cy = r.centerY()

        if (key.type == KeyType.ENTER) {
            // Inverted round bubble (pill)
            fill.color = if (pressed) ColorUtils.blendARGB(t.accent, Color.GRAY, 0.25f) else t.accent
            canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
            drawIcon(canvas, R.drawable.ic_enter, cx, cy, dp(26f), t.onAccent)
            return
        }

        val capsOn = key.type == KeyType.SHIFT && shiftState == ShiftState.CAPS
        val base = when {
            capsOn -> t.inverted
            key.type == KeyType.SHIFT && shiftState == ShiftState.ONE_SHOT -> t.key
            key.tinted -> t.specialKey
            key.type == KeyType.CHAR || key.type == KeyType.SPACE || key.type == KeyType.GLOBE -> t.key
            else -> t.specialKey
        }
        fill.color = if (pressed) ColorUtils.blendARGB(base, Color.WHITE, 0.18f) else base
        val radius = if (key.type == KeyType.SYMBOLS || key.type == KeyType.LETTERS) {
            r.height() / 2f
        } else {
            dp(t.keyRadiusDp)
        }
        canvas.drawRoundRect(r, radius, radius, fill)

        val iconColor = if (capsOn) t.onInverted else t.icon
        when (key.type) {
            KeyType.SHIFT -> drawIcon(canvas, R.drawable.ic_shift, cx, cy, dp(26f), iconColor)
            KeyType.BACKSPACE -> drawIcon(canvas, R.drawable.ic_backspace, cx, cy, dp(26f), t.icon)
            KeyType.GLOBE -> drawIcon(canvas, R.drawable.ic_globe, cx, cy, dp(24f), t.icon)
            KeyType.COMMA_EMOJI -> {
                drawIcon(canvas, R.drawable.ic_smile, cx, cy - dp(9f), dp(15f), t.icon)
                drawLabel(canvas, ",", cx, cy + dp(7f), sp(20f), t.text)
            }
            KeyType.CHAR -> drawLabel(canvas, labelFor(key), cx, cy, sp(22f), t.text)
            KeyType.SYMBOLS, KeyType.LETTERS -> drawLabel(canvas, key.label, cx, cy, sp(15f), t.text)
            KeyType.SPACE -> drawLabel(canvas, key.label, cx, cy, sp(15f), t.textDim)
            else -> Unit
        }
    }

    /** Letter preview bubble above the pressed key (off by default). */
    private fun drawPreviews(canvas: Canvas, t: KeyboardTheme) {
        if (!previewEnabled) return
        for (tg in pressedByPointer.values) {
            val key = tg.key ?: continue
            if (key.type != KeyType.CHAR) continue
            val r = tg.rect
            val bw = r.width() + dp(14f)
            val bh = dp(54f)
            val left = (r.centerX() - bw / 2f).coerceIn(dp(10f), max(dp(10f), width - dp(10f) - bw))
            val top = r.top - bh + dp(8f)
            tmpRect.set(left, top, left + bw, top + bh)
            fill.color = ColorUtils.blendARGB(t.key, Color.WHITE, 0.22f)
            canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), fill)
            drawLabel(canvas, labelFor(key), tmpRect.centerX(), tmpRect.centerY() - dp(3f), sp(30f), t.text)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.textSize = size
        textPaint.color = color
        val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, cx, y, textPaint)
    }

    private fun drawLeftLabel(canvas: Canvas, text: String, x: Float, cy: Float, size: Float, color: Int) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = Paint.Align.LEFT
        val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, y, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawIcon(canvas: Canvas, resId: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val d = iconCache.getOrPut(resId) { ContextCompat.getDrawable(context, resId)!!.mutate() }
        d.setTint(color)
        val half = size / 2f
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(canvas)
    }

    // ---------- touch ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                if (floating && gripPointer == -1 && i == 0 && y < handleH + dp(6f)) {
                    gripPointer = event.getPointerId(i)
                    gripRawX = event.rawX
                    gripRawY = event.rawY
                    return true
                }
                val scrollPanel = panel == Panel.EMOJI || panel == Panel.CLIPBOARD
                if (scrollPanel && gridPointer == -1 && contentRect.contains(x, y)) {
                    gridPointer = event.getPointerId(i)
                    gridDownY = y
                    gridLastY = y
                    gridDownX = x
                    gridLastX = x
                    gridMoved = false
                    return true
                }
                val tg = hit(x, y)
                if (tg != null) {
                    pressedByPointer[event.getPointerId(i)] = tg
                    Feedback.tap(this, prefs)
                    val type = tg.key?.type
                    if (type == KeyType.BACKSPACE) {
                        backspacePointer = event.getPointerId(i)
                        backspaceDownY = y
                        wordDeleteFired = false
                        fireBackspace()
                        uiHandler.postDelayed(repeatRunnable, 380L)
                    } else if (type == KeyType.COMMA_EMOJI) {
                        longPressCandidate = tg
                        longPressFired = null
                        uiHandler.postDelayed(longPressRunnable, 400L)
                    } else if (tg.suggestionIndex >= 0 &&
                        suggestions.getOrNull(tg.suggestionIndex) != null &&
                        !(composingActive && tg.suggestionIndex == 0)
                    ) {
                        longPressCandidate = tg
                        longPressFired = null
                        uiHandler.postDelayed(longPressRunnable, 400L)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gripPointer != -1) {
                    val dx = event.rawX - gripRawX
                    val dy = event.rawY - gripRawY
                    gripRawX = event.rawX
                    gripRawY = event.rawY
                    dragListener?.invoke(dx, dy)
                    return true
                }
                if (backspacePointer != -1 && !wordDeleteFired) {
                    val idx = event.findPointerIndex(backspacePointer)
                    if (idx >= 0 && backspaceDownY - event.getY(idx) > dp(26f)) {
                        wordDeleteFired = true
                        uiHandler.removeCallbacks(repeatRunnable)
                        listener?.onDeleteWord()
                        Feedback.tap(this, prefs)
                    }
                }
                if (gridPointer != -1) {
                    val idx = event.findPointerIndex(gridPointer)
                    if (idx >= 0) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        if (panel == Panel.CLIPBOARD) {
                            if (!gridMoved && abs(y - gridDownY) > touchSlop) gridMoved = true
                            if (gridMoved) {
                                clipScrollY = (clipScrollY + gridLastY - y)
                                    .coerceIn(0f, maxClipScroll())
                                invalidate()
                            }
                            gridLastY = y
                        } else {
                            if (!gridMoved && abs(y - gridDownY) > touchSlop) gridMoved = true
                            if (gridMoved) {
                                scrollY += gridLastY - y
                                invalidate()
                            }
                            gridLastY = y
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                if (id == gripPointer) {
                    gripPointer = -1
                    dragEndListener?.invoke()
                    return true
                }
                if (id == gridPointer) {
                    if (!gridMoved) {
                        if (panel == Panel.EMOJI) {
                            tapEmoji(event.getX(i), event.getY(i))
                        } else if (panel == Panel.CLIPBOARD) {
                            tapClip(event.getX(i), event.getY(i))
                        }
                    }
                    gridPointer = -1
                    invalidate()
                    return true
                }
                val down = pressedByPointer.remove(id)
                if (down != null) {
                    val type = down.key?.type
                    if (type == KeyType.BACKSPACE) {
                        uiHandler.removeCallbacks(repeatRunnable)
                        backspacePointer = -1
                        wordDeleteFired = false
                    } else if (down === longPressFired) {
                        longPressFired = null
                    } else {
                        if (type == KeyType.COMMA_EMOJI || down.suggestionIndex >= 0) {
                            uiHandler.removeCallbacks(longPressRunnable)
                        }
                        val slop = dp(10f)
                        slopRect.set(down.rect)
                        slopRect.inset(-slop, -slop)
                        if (slopRect.contains(event.getX(i), event.getY(i))) activate(down)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedByPointer.clear()
                gridPointer = -1
                if (gripPointer != -1) {
                    gripPointer = -1
                    dragEndListener?.invoke()
                }
                backspacePointer = -1
                wordDeleteFired = false
                uiHandler.removeCallbacks(repeatRunnable)
                uiHandler.removeCallbacks(longPressRunnable)
                invalidate()
            }
        }
        return true
    }

    private fun activate(tg: Target) {
        val l = listener ?: return
        if (tg.suggestionIndex >= 0) {
            if (laneVisible()) return
            val word = suggestions.getOrNull(tg.suggestionIndex)
            if (word != null) l.onSuggestion(word)
            return
        }
        if (tg.resultIndex >= 0) {
            val emoji = searchResults.getOrNull(tg.resultIndex)
            if (emoji != null) {
                recentsStore.add(emoji)
                l.onEmoji(emoji)
            }
            return
        }
        if (tg.tabIndex >= 0) {
            if (tg.tabIndex == 0) {
                startCapture(Capture.EMOJI_SEARCH)
                return
            }
            val maxScroll = max(0f, sheetHeight - contentRect.height())
            scrollY = sectionStarts.getOrElse(tg.tabIndex - 1) { 0f }.coerceAtMost(maxScroll)
            invalidate()
            return
        }
        val action = tg.action
        if (action != null) {
            if (tg.tray) runTrayAction(action) else handleAction(action)
            return
        }
        val key = tg.key ?: return
        when (key.type) {
            KeyType.CHAR -> {
                if (captureMode != Capture.NONE) captureAppend(labelFor(key)) else l.onText(labelFor(key))
                if (shiftState == ShiftState.ONE_SHOT) setShift(ShiftState.OFF)
            }
            KeyType.SHIFT -> {
                val l = listener
                if (l != null) l.onShift() else toggleShift()
            }
            KeyType.SYMBOLS -> setSymbols(true)
            KeyType.LETTERS -> setSymbols(false)
            KeyType.GLOBE -> l.onGlobe()
            KeyType.COMMA_EMOJI -> if (captureMode != Capture.NONE) captureAppend(",") else l.onText(",")
            KeyType.SPACE -> if (captureMode != Capture.NONE) captureAppend(" ") else l.onSpace()
            KeyType.ENTER -> when (captureMode) {
                Capture.NONE -> l.onEnter()
                Capture.STICKER -> sendSticker()
                Capture.EMOJI_SEARCH -> Unit
            }
            KeyType.BACKSPACE, KeyType.SPACER -> Unit
        }
    }

    private fun runTrayAction(action: ToolbarAction) {
        trayOpen = false
        trayPage = 0
        rebuildTargets()
        when (action) {
            ToolbarAction.EMOJI -> openEmoji()
            ToolbarAction.CLIPBOARD -> openClipboard()
            ToolbarAction.STICKER -> startCapture(Capture.STICKER)
            else -> handleAction(action)
        }
        invalidate()
    }

    private fun handleAction(action: ToolbarAction) {
        when (action) {
            ToolbarAction.MENU -> {
                trayOpen = true
                trayPage = 0
                rebuildTargets()
                invalidate()
            }
            ToolbarAction.CLOSE -> closeTray()
            ToolbarAction.TRAY_PREV, ToolbarAction.TRAY_NEXT -> {
                if (trayPageCount > 0) {
                    val delta = if (action == ToolbarAction.TRAY_NEXT) 1 else -1
                    trayPage = (trayPage + delta + trayPageCount) % trayPageCount
                    rebuildTargets()
                    invalidate()
                }
            }
            ToolbarAction.EMOJI -> openEmoji()
            ToolbarAction.CLIPBOARD -> openClipboard()
            ToolbarAction.BACK -> {
                if (captureMode != Capture.NONE) {
                    exitCapture()
                } else {
                    panel = Panel.KEYS
                    scrollY = 0f
                    rebuildTargets()
                    invalidate()
                }
            }
            ToolbarAction.STICKER -> startCapture(Capture.STICKER)
            ToolbarAction.STICKER_STYLE -> {
                stickerPalette = (stickerPalette + 1) % StickerMaker.palettes.size
                invalidate()
            }
            ToolbarAction.STICKER_FONT -> {
                stickerStyle = (stickerStyle + 1) % StickerMaker.styles.size
                invalidate()
            }
            ToolbarAction.STICKER_SEND -> sendSticker()
            ToolbarAction.CLIP_PAUSE -> {
                clipStore.paused = !clipStore.paused
                reloadClipboard()
                invalidate()
            }
            ToolbarAction.CLIP_EDIT -> {
                if (clipEditMode) {
                    // The pencil becomes a bin: tapping it clears the unpinned clips.
                    clipStore.clearUnpinned()
                    clipScrollY = 0f
                    clipEditMode = false
                } else {
                    clipEditMode = true
                }
                reloadClipboard()
                invalidate()
            }
            ToolbarAction.COPY_PASTE -> {
                val text = copiedChip
                copiedChip = null
                uiHandler.removeCallbacks(clearChipRunnable)
                rebuildTargets()
                invalidate()
                if (text != null) listener?.onPaste(text)
            }
            else -> listener?.onToolbarAction(action)
        }
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        val next = when (shiftState) {
            ShiftState.OFF -> ShiftState.ONE_SHOT
            ShiftState.ONE_SHOT -> if (now - lastShiftTap < 350L) ShiftState.CAPS else ShiftState.OFF
            ShiftState.CAPS -> ShiftState.OFF
        }
        lastShiftTap = now
        setShift(next)
    }
}

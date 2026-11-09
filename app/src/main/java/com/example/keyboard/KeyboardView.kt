package com.example.keyboard

import android.content.Context
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
    MENU, CLOSE, MIC, EMOJI, CLIPBOARD, THEME, LANGUAGE, SETTINGS, BACK, CLIP_PAUSE, CLIP_CLEAR, COPY_PASTE,
    UNDO, REDO, STICKER, STICKER_STYLE, STICKER_FONT, STICKER_SEND,
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
    )

    private class SheetItem(
        val y: Float,
        val height: Float,
        val header: String? = null,
        val emojis: List<String>? = null,
        val hint: String? = null,
    )

    private class ClipRow(
        val x: Float,
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
    private var clipRows: List<ClipRow> = emptyList()
    private var clipHeight = 0f
    private var clipScrollX = 0f
    private var clipCardW = 0f
    private val clipHeaderH = dp(38f)

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
            handleAction(ToolbarAction.EMOJI)
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

    /** A transparent strip above the keyboard where the mascot can stand. */
    private val mascotBandH: Float
        get() = if (mascotEnabled && placement != MascotPlacement.TOOLBAR &&
            !(placement == MascotPlacement.CURSOR && overlayMascot)
        ) {
            dp(34f)
        } else {
            0f
        }

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

    fun setSuggestions(list: List<String>) {
        suggestions = list
        invalidate()
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
            val clearCx = w - dp(28f)
            targets.add(
                Target(
                    RectF(pauseCx - dp(22f), cy - dp(22f), pauseCx + dp(22f), cy + dp(22f)),
                    action = ToolbarAction.CLIP_PAUSE,
                ),
            )
            targets.add(
                Target(
                    RectF(clearCx - dp(22f), cy - dp(22f), clearCx + dp(22f), cy + dp(22f)),
                    action = ToolbarAction.CLIP_CLEAR,
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
            val actions = listOf(
                ToolbarAction.UNDO,
                ToolbarAction.REDO,
                ToolbarAction.EMOJI,
                ToolbarAction.STICKER,
                ToolbarAction.CLIPBOARD,
                ToolbarAction.MIC,
                ToolbarAction.THEME,
                ToolbarAction.LANGUAGE,
                ToolbarAction.SETTINGS,
            )
            val startX = dp(60f)
            val endX = w - dp(16f)
            val step = (endX - startX) / actions.size
            actions.forEachIndexed { i, a ->
                val cx = startX + step * (i + 0.5f)
                targets.add(Target(RectF(cx - step / 2f, topOffset(), cx + step / 2f, topOffset() + toolbarH), action = a))
            }
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

        if (panel == Panel.EMOJI) buildEmojiTargets(w) else buildKeyTargets(w)
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
        val bottomH = dp(42f)
        val bottomTop = top + keysHeight() - bottomH
        contentRect.set(left, top + tabsH + dp(2f), right, bottomTop - dp(2f))
        val bw = dp(72f)
        targets.add(Target(RectF(left, bottomTop, left + bw, bottomTop + bottomH), key = abcKey))
        targets.add(
            Target(RectF(left + bw + keyGap, bottomTop, right - bw - keyGap, bottomTop + bottomH), key = panelSpaceKey),
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
        clipScrollX = 0f
        rebuildTargets()
        reloadClipboard()
        invalidate()
    }

    private fun reloadClipboard() {
        val items = clipStore.items().sortedByDescending { it.pinned }
        val rows = ArrayList<ClipRow>()
        val gap = dp(10f)
        val cardH = if (contentRect.height() > 0f) {
            (contentRect.height() - clipHeaderH - dp(8f)).coerceIn(dp(96f), dp(122f))
        } else {
            dp(112f)
        }
        clipCardW = (contentRect.width() * 0.78f).coerceAtMost(dp(300f))
        if (items.isEmpty()) {
            val msg = if (clipStore.paused) "Clipboard history is paused" else "Copied text appears here"
            rows.add(ClipRow(0f, contentRect.width(), cardH, hint = msg))
            clipHeight = cardH
        } else {
            var x = 0f
            for (item in items) {
                rows.add(ClipRow(x, clipCardW, cardH, item = item))
                x += clipCardW + gap
            }
            clipHeight = cardH
        }
        clipRows = rows
        clipScrollX = clipScrollX.coerceIn(0f, max(0f, totalClipWidth() - contentRect.width()))
    }

    private fun totalClipWidth(): Float {
        if (clipRows.isEmpty()) return contentRect.width()
        val last = clipRows.last()
        return last.x + last.width
    }

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
        if (y < contentRect.top + clipHeaderH) {
            // "Clear" lives in the sheet header.
            if (x > contentRect.right - dp(90f)) {
                clipStore.clearUnpinned()
                clipScrollX = 0f
                reloadClipboard()
                invalidate()
            }
            return
        }
        val cardTop = contentRect.top + clipHeaderH + dp(4f)
        if (y < cardTop || y > cardTop + clipHeight) return
        val localX = x - contentRect.left + clipScrollX
        val row = clipRows.firstOrNull { localX >= it.x && localX < it.x + it.width } ?: return
        val item = row.item ?: return
        val cardLeft = contentRect.left + row.x - clipScrollX
        val rx = x - cardLeft
        val ry = y - cardTop
        Feedback.tap(this, prefs)
        when {
            rx < dp(34f) && ry < dp(36f) -> {
                clipStore.remove(item)
                reloadClipboard()
            }
            rx > row.width - dp(34f) && ry < dp(36f) -> {
                clipStore.togglePin(item)
                reloadClipboard()
            }
            item.image == null -> listener?.onPaste(item.text)
            else -> listener?.onPasteImage(item)
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
            (fieldEmpty || deletingRecently())

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
                action != null -> drawToolbarButton(canvas, tg, action, t)
                key != null -> drawKey(canvas, tg, key, t)
            }
        }

        when (panel) {
            Panel.EMOJI -> drawEmojiSheet(canvas, t)
            Panel.CLIPBOARD -> drawClipboardList(canvas, t)
            Panel.KEYS -> drawPreviews(canvas, t)
        }

        if (lane) {
            drawMascotLane(canvas, t)
            postInvalidateOnAnimation()
        } else if (mascotEnabled && placement != MascotPlacement.TOOLBAR && mascotVisible()) {
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
            action == ToolbarAction.CLIP_CLEAR ||
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
            ToolbarAction.UNDO -> R.drawable.ic_undo
            ToolbarAction.REDO -> R.drawable.ic_redo
            ToolbarAction.COPY_PASTE -> R.drawable.ic_clipboard
            ToolbarAction.STICKER -> R.drawable.ic_sticker
            ToolbarAction.STICKER_STYLE -> R.drawable.ic_theme
            ToolbarAction.STICKER_FONT -> R.drawable.ic_text
            ToolbarAction.STICKER_SEND -> R.drawable.ic_send
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
                textPaint.textSize = sp(24f)
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

    private fun drawClipboardList(canvas: Canvas, t: KeyboardTheme) {
        clipScrollX = clipScrollX.coerceIn(0f, max(0f, totalClipWidth() - contentRect.width()))
        scrollY = 0f

        canvas.save()
        canvas.clipRect(contentRect)

        // Sheet header: title on the left, a quiet "Clear" on the right.
        val headerCy = contentRect.top + clipHeaderH / 2f
        drawLeftLabel(canvas, "Clipboard", contentRect.left + dp(6f), headerCy, sp(13f), t.text)
        val pinnedCount = clipRows.count { it.item?.pinned == true }
        val clearable = clipRows.count { it.item != null && !it.item.pinned }
        if (clearable > 0) {
            drawLeftLabel(
                canvas, "Clear",
                contentRect.right - dp(80f), headerCy, sp(12f),
                if (pinnedCount > 0) t.textDim else t.accent,
            )
        }

        val cardTop = contentRect.top + clipHeaderH + dp(4f)
        for (row in clipRows) {
            val left = contentRect.left + row.x - clipScrollX
            if (left + row.width < contentRect.left - dp(8f) || left > contentRect.right + dp(8f)) continue
            val item = row.item
            val hint = row.hint
            if (item != null) {
                tmpRect.set(left, cardTop, left + row.width, cardTop + row.height)
                fill.color = t.card
                canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), fill)
                if (item.pinned) {
                    clipStroke.color = t.accent
                    clipStroke.strokeWidth = dp(1.5f)
                    tmpRect.inset(dp(1f), dp(1f))
                    canvas.drawRoundRect(tmpRect, dp(17f), dp(17f), clipStroke)
                    tmpRect.inset(-dp(1f), -dp(1f))
                }

                // Tiny icon buttons: delete on the left, pin on the right.
                drawIcon(canvas, R.drawable.ic_close, tmpRect.left + dp(17f), tmpRect.top + dp(18f), dp(15f), t.textDim)
                val pinColor = if (item.pinned) t.accent else t.textDim
                drawIcon(
                    canvas,
                    if (item.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,
                    tmpRect.right - dp(17f), tmpRect.top + dp(18f), dp(17f), pinColor,
                )

                // Paste pill, bottom right.
                val pillW = dp(58f)
                val pillH = dp(24f)
                tmpRectThumb.set(
                    tmpRect.right - dp(12f) - pillW,
                    tmpRect.bottom - dp(11f) - pillH,
                    tmpRect.right - dp(12f),
                    tmpRect.bottom - dp(11f),
                )
                fill.color = t.accent
                canvas.drawRoundRect(tmpRectThumb, pillH / 2f, pillH / 2f, fill)
                drawLabel(
                    canvas, "Paste",
                    tmpRectThumb.centerX(), tmpRectThumb.centerY(),
                    sp(11f), t.onAccent,
                )

                // Two-line snippet, sitting under the icon row.
                var textLeft = tmpRect.left + dp(14f)
                val textTop = tmpRect.top + dp(30f)
                val textRight = tmpRect.right - dp(14f)
                if (item.image != null) {
                    val thumb = thumbnailFor(item)
                    if (thumb != null) {
                        val side = dp(34f)
                        tmpRectThumb.set(
                            textLeft, textTop,
                            textLeft + side, textTop + side,
                        )
                        canvas.save()
                        path.reset()
                        path.addRoundRect(tmpRectThumb, dp(9f), dp(9f), Path.Direction.CW)
                        canvas.clipPath(path)
                        canvas.drawBitmap(thumb, null, tmpRectThumb, imagePaint)
                        canvas.restore()
                        textLeft = tmpRectThumb.right + dp(9f)
                    }
                }
                textPaint.textSize = sp(13f)
                textPaint.color = t.text
                textPaint.textAlign = Paint.Align.LEFT
                val maxW = (textRight - textLeft).coerceAtLeast(dp(20f))
                val visible = if (item.image != null) "Image" else item.text.replace('\n', ' ')
                val lines = wrapTwoLines(visible, maxW)
                var baseline = textTop - (textPaint.descent() + textPaint.ascent()) / 2f
                for (line in lines) {
                    canvas.drawText(line, textLeft, baseline, textPaint)
                    baseline += textPaint.descent() - textPaint.ascent()
                }

                // Time, tucked above the Paste pill.
                textPaint.textSize = sp(10f)
                textPaint.color = t.textDim
                drawLeftLabel(
                    canvas, relativeTime(item.time),
                    tmpRect.left + dp(14f), tmpRectThumb.centerY(),
                    sp(10f), t.textDim,
                )
                textPaint.textAlign = Paint.Align.CENTER
            } else if (hint != null) {
                drawLabel(canvas, hint, contentRect.centerX(), cardTop + row.height / 2f, sp(14f), t.textDim)
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
                            if (!gridMoved && abs(x - gridDownX) > touchSlop) gridMoved = true
                            if (gridMoved) {
                                clipScrollX -= x - gridLastX
                                clipScrollX = clipScrollX.coerceIn(
                                    0f, max(0f, totalClipWidth() - contentRect.width()),
                                )
                                invalidate()
                            }
                            gridLastX = x
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
                        if (type == KeyType.COMMA_EMOJI) uiHandler.removeCallbacks(longPressRunnable)
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
            handleAction(action)
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

    private fun handleAction(action: ToolbarAction) {
        when (action) {
            ToolbarAction.MENU -> {
                trayOpen = true
                rebuildTargets()
                invalidate()
            }
            ToolbarAction.CLOSE -> closeTray()
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
            ToolbarAction.CLIP_CLEAR -> {
                clipStore.clearUnpinned()
                scrollY = 0f
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

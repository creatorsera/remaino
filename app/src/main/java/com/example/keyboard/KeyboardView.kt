package com.example.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
    MENU, CLOSE, MIC, EMOJI, CLIPBOARD, THEME, LANGUAGE, SETTINGS, BACK, CLIP_PAUSE, CLIP_CLEAR,
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
        fun onGlobe()
        fun onEmoji(emoji: String)
        fun onPaste(text: String)
        fun onToolbarAction(action: ToolbarAction)
    }

    private enum class Panel { KEYS, EMOJI, CLIPBOARD }

    private class Target(
        val rect: RectF,
        val key: Key? = null,
        val action: ToolbarAction? = null,
        val suggestionIndex: Int = -1,
        val tabIndex: Int = -1,
    )

    private class SheetItem(
        val y: Float,
        val height: Float,
        val header: String? = null,
        val emojis: List<String>? = null,
        val hint: String? = null,
    )

    private class ClipRow(
        val y: Float,
        val height: Float,
        val header: String? = null,
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
    private var suggestions: List<String> = emptyList()
    private var navInset = 0
    private var lastShiftTap = 0L

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
        R.drawable.ic_clock, R.drawable.ic_smile, R.drawable.ic_person, R.drawable.ic_paw,
        R.drawable.ic_cup, R.drawable.ic_ball, R.drawable.ic_car, R.drawable.ic_bulb, R.drawable.ic_heart,
    )

    // Clipboard list
    private val clipStore = ClipboardStore.get(context)
    private var clipRows: List<ClipRow> = emptyList()
    private var clipHeight = 0f

    private val abcKey = Key("ABC", KeyType.LETTERS)
    private val panelSpaceKey = Key("", KeyType.SPACE)
    private val panelBackspaceKey = Key("", KeyType.BACKSPACE)

    private val targets = ArrayList<Target>()
    private val pressedByPointer = HashMap<Int, Target>()
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
    private val slopRect = RectF()

    private var longPressCandidate: Target? = null
    private var longPressFired: Target? = null
    private val longPressRunnable = Runnable {
        val tg = longPressCandidate
        if (tg != null && pressedByPointer.values.any { it === tg }) {
            longPressFired = tg
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
    private val rowH: Float get() = dp(46f)
    private val rowGap: Float get() = dp(4f)
    private val sideMargin: Float get() = dp(1.5f)
    private val keyGap: Float get() = dp(2f)
    private val topPad: Float get() = dp(6f)
    private val bottomPad: Float get() = max(dp(14f), navInset.toFloat())

    private fun rowCount(): Int = Layouts.rowCount(showNumberRow)
    private fun keysTop(): Float = toolbarH + topPad
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

    fun applySettings(numberRow: Boolean, preview: Boolean, mascot: Boolean) {
        previewEnabled = preview
        mascotEnabled = mascot
        if (numberRow != showNumberRow) {
            showNumberRow = numberRow
            requestLayout()
            rebuildTargets()
        }
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
        val cy = toolbarH / 2f
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

        targets.add(Target(leftRect, action = if (trayOpen) ToolbarAction.CLOSE else ToolbarAction.MENU))

        if (trayOpen) {
            val actions = listOf(
                ToolbarAction.EMOJI,
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
                targets.add(Target(RectF(cx - step / 2f, 0f, cx + step / 2f, toolbarH), action = a))
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
            val gap = dp(6f)
            val chipW = (regionR - regionL - 2 * gap) / 3f
            for (i in 0 until 3) {
                val l = regionL + i * (chipW + gap)
                targets.add(Target(RectF(l, cy - dp(16f), l + chipW, cy + dp(16f)), suggestionIndex = i))
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
        val tabs = EmojiData.categories.size + 1
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
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        recentsStore.add(emoji)
        listener?.onEmoji(emoji)
    }

    // ---------- clipboard list ----------

    private fun openClipboard() {
        panel = Panel.CLIPBOARD
        scrollY = 0f
        rebuildTargets()
        reloadClipboard()
        invalidate()
    }

    private fun reloadClipboard() {
        val items = clipStore.items()
        val rows = ArrayList<ClipRow>()
        val headerH = dp(26f)
        val itemH = dp(44f)
        val hintH = dp(70f)
        var y = 0f
        if (items.isEmpty()) {
            val msg = if (clipStore.paused) "Clipboard history is paused" else "Copied text appears here"
            rows.add(ClipRow(y, hintH, hint = msg))
            y += hintH
        } else {
            val pinned = items.filter { it.pinned }
            val recent = items.filter { !it.pinned }
            if (pinned.isNotEmpty()) {
                rows.add(ClipRow(y, headerH, header = "Pinned"))
                y += headerH
                for (item in pinned) {
                    rows.add(ClipRow(y, itemH, item = item))
                    y += itemH
                }
            }
            if (recent.isNotEmpty()) {
                rows.add(ClipRow(y, headerH, header = "Recent"))
                y += headerH
                for (item in recent) {
                    rows.add(ClipRow(y, itemH, item = item))
                    y += itemH
                }
            }
        }
        clipRows = rows
        clipHeight = y
    }

    private fun tapClip(x: Float, y: Float) {
        if (!contentRect.contains(x, y)) return
        val local = y - contentRect.top + scrollY
        val row = clipRows.firstOrNull { local >= it.y && local < it.y + it.height } ?: return
        val item = row.item ?: return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when {
            x > contentRect.right - dp(44f) -> {
                clipStore.remove(item.text)
                reloadClipboard()
            }
            x > contentRect.right - dp(88f) -> {
                clipStore.togglePin(item.text)
                reloadClipboard()
            }
            else -> listener?.onPaste(item.text)
        }
        invalidate()
    }

    // ---------- mascot ----------

    private fun fireBackspace() {
        listener?.onBackspace()
        noteDelete()
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
        postInvalidateOnAnimation()
    }

    private fun laneVisible(): Boolean =
        mascotEnabled && !trayOpen && panel != Panel.CLIPBOARD &&
            SystemClock.uptimeMillis() - lastDeleteAt < 900L

    private fun drawMascotLane(canvas: Canvas, t: KeyboardTheme) {
        val left = dp(56f)
        val right = width - dp(56f)
        val cy = toolbarH / 2f
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

        // The mascot, pushing it with one arm
        val bx = caretX + dp(21f)
        val bob = if (moving) sin(now / 60.0).toFloat() * dp(1.5f) else 0f
        val by = cy + bob
        strokePaint.color = t.inverted
        strokePaint.strokeWidth = dp(2.5f)
        canvas.drawLine(bx - dp(9f), by + dp(2f), caretX + dp(3f), by, strokePaint)
        fill.color = t.inverted
        canvas.drawCircle(bx, by, dp(11f), fill)
        strokePaint.strokeWidth = dp(1.6f)
        canvas.drawLine(bx, by - dp(11f), bx + dp(2f), by - dp(15f), strokePaint)
        canvas.drawCircle(bx + dp(3.5f), by - dp(15f), dp(2.2f), fill)
        val step = if (moving) sin(now / 70.0).toFloat() * dp(2f) else 0f
        canvas.drawCircle(bx - dp(4f) + step, by + dp(11f), dp(2.6f), fill)
        canvas.drawCircle(bx + dp(4f) - step, by + dp(11f), dp(2.6f), fill)
        fill.color = t.onInverted
        canvas.drawCircle(bx - dp(3.8f), by - dp(2f), dp(1.6f), fill)
        canvas.drawCircle(bx + dp(3.8f), by - dp(2f), dp(1.6f), fill)
        strokePaint.color = t.onInverted
        strokePaint.strokeWidth = dp(1.4f)
        tmpRect.set(bx - dp(3f), by + dp(0.5f), bx + dp(3f), by + dp(5.5f))
        canvas.drawArc(tmpRect, 20f, 140f, false, strokePaint)
    }

    // ---------- hit testing ----------

    private fun hit(x: Float, y: Float): Target? {
        for (tg in targets) {
            if (tg.rect.contains(x, y)) return tg
        }
        if (y >= toolbarH) {
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

        drawTopRounded(canvas, h, w, r, t.base)
        drawTopRounded(canvas, toolbarH, w, r, t.toolbar)

        if (panel == Panel.CLIPBOARD) {
            drawLeftLabel(canvas, "Clipboard", dp(56f), toolbarH / 2f, sp(16f), t.text)
        } else if (sensitive && !trayOpen) {
            drawLabel(canvas, "Private field", w / 2f, toolbarH / 2f, sp(14f), t.textDim)
        }

        for (tg in targets) {
            val key = tg.key
            val action = tg.action
            when {
                tg.suggestionIndex >= 0 -> if (!lane) drawChip(canvas, tg, t)
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
        }
    }

    private fun drawTopRounded(canvas: Canvas, bottom: Float, w: Float, r: Float, color: Int) {
        fill.color = color
        path.reset()
        tmpRect.set(0f, 0f, w, bottom)
        path.addRoundRect(tmpRect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        canvas.drawPath(path, fill)
    }

    private fun drawToolbarButton(canvas: Canvas, tg: Target, action: ToolbarAction, t: KeyboardTheme) {
        val cx = tg.rect.centerX()
        val cy = tg.rect.centerY()
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
        val selected = tg.tabIndex == currentSection()
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
        val maxScroll = max(0f, clipHeight - contentRect.height())
        scrollY = scrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(contentRect)
        for (row in clipRows) {
            val top = contentRect.top + row.y - scrollY
            if (top + row.height < contentRect.top || top > contentRect.bottom) continue
            val header = row.header
            val item = row.item
            val hint = row.hint
            if (header != null) {
                drawLeftLabel(canvas, header, contentRect.left + dp(6f), top + row.height / 2f, sp(12f), t.textDim)
            } else if (item != null) {
                tmpRect.set(contentRect.left, top + dp(2f), contentRect.right, top + row.height - dp(2f))
                fill.color = t.key
                canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), fill)
                textPaint.textSize = sp(14f)
                textPaint.color = t.text
                textPaint.textAlign = Paint.Align.LEFT
                val maxW = tmpRect.width() - dp(14f) - dp(88f)
                val shown = TextUtils.ellipsize(item.text.replace('\n', ' '), textPaint, maxW, TextUtils.TruncateAt.END)
                val y = tmpRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(shown, 0, shown.length, tmpRect.left + dp(14f), y, textPaint)
                textPaint.textAlign = Paint.Align.CENTER
                val pinColor = if (item.pinned) t.inverted else t.textDim
                drawIcon(
                    canvas,
                    if (item.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,
                    tmpRect.right - dp(66f), tmpRect.centerY(), dp(20f), pinColor,
                )
                drawIcon(canvas, R.drawable.ic_close, tmpRect.right - dp(22f), tmpRect.centerY(), dp(18f), t.textDim)
            } else if (hint != null) {
                drawLabel(canvas, hint, contentRect.centerX(), top + row.height / 2f, sp(14f), t.textDim)
            }
        }
        canvas.restore()
    }

    private fun drawKey(canvas: Canvas, tg: Target, key: Key, t: KeyboardTheme) {
        val r = tg.rect
        val pressed = isPressed(tg)
        val cx = r.centerX()
        val cy = r.centerY()

        if (key.type == KeyType.ENTER) {
            // Inverted round bubble (pill)
            fill.color = if (pressed) ColorUtils.blendARGB(t.inverted, Color.GRAY, 0.25f) else t.inverted
            canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
            drawIcon(canvas, R.drawable.ic_enter, cx, cy, dp(26f), t.onInverted)
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
                val scrollPanel = panel == Panel.EMOJI || panel == Panel.CLIPBOARD
                if (scrollPanel && gridPointer == -1 && contentRect.contains(x, y)) {
                    gridPointer = event.getPointerId(i)
                    gridDownY = y
                    gridLastY = y
                    gridMoved = false
                    return true
                }
                val tg = hit(x, y)
                if (tg != null) {
                    pressedByPointer[event.getPointerId(i)] = tg
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val type = tg.key?.type
                    if (type == KeyType.BACKSPACE) {
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
                if (gridPointer != -1) {
                    val idx = event.findPointerIndex(gridPointer)
                    if (idx >= 0) {
                        val y = event.getY(idx)
                        if (!gridMoved && abs(y - gridDownY) > touchSlop) gridMoved = true
                        if (gridMoved) {
                            scrollY += gridLastY - y
                            invalidate()
                        }
                        gridLastY = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
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
        if (tg.tabIndex >= 0) {
            val maxScroll = max(0f, sheetHeight - contentRect.height())
            scrollY = sectionStarts.getOrElse(tg.tabIndex) { 0f }.coerceAtMost(maxScroll)
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
                l.onText(labelFor(key))
                if (shiftState == ShiftState.ONE_SHOT) setShift(ShiftState.OFF)
            }
            KeyType.SHIFT -> toggleShift()
            KeyType.SYMBOLS -> setSymbols(true)
            KeyType.LETTERS -> setSymbols(false)
            KeyType.GLOBE -> l.onGlobe()
            KeyType.COMMA_EMOJI -> l.onText(",")
            KeyType.SPACE -> l.onSpace()
            KeyType.ENTER -> l.onEnter()
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
                panel = Panel.KEYS
                scrollY = 0f
                rebuildTargets()
                invalidate()
            }
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

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
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

enum class ShiftState { OFF, ONE_SHOT, CAPS }

enum class ToolbarAction { MENU, CLOSE, MIC, EMOJI, CLIPBOARD, THEME, LANGUAGE, SETTINGS }

/**
 * The whole keyboard drawn on one canvas: a rounded top toolbar (suggestions and icons)
 * above the keys. Faster than one view per key.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onSpace()
        fun onEnter()
        fun onSuggestion(word: String)
        fun onGlobe()
        fun onToolbarAction(action: ToolbarAction)
    }

    private class Target(
        val rect: RectF,
        val key: Key? = null,
        val action: ToolbarAction? = null,
        val suggestionIndex: Int = -1,
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

    private var trayOpen = false
    private var sensitive = false
    private var suggestions: List<String> = emptyList()
    private var navInset = 0
    private var lastShiftTap = 0L

    private val targets = ArrayList<Target>()
    private val pressedByPointer = HashMap<Int, Target>()
    private val iconCache = HashMap<Int, Drawable>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val tmpRect = RectF()
    private val slopRect = RectF()

    private val repeatRunnable = object : Runnable {
        override fun run() {
            listener?.onBackspace()
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

    private val toolbarH: Float get() = dp(52f)
    private val rowH: Float get() = dp(46f)
    private val rowGap: Float get() = dp(6f)
    private val sideMargin: Float get() = dp(6f)
    private val keyGap: Float get() = dp(4f)
    private val topPad: Float get() = dp(8f)
    private val bottomPad: Float get() = max(dp(14f), navInset.toFloat())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (toolbarH + topPad + 4 * rowH + 3 * rowGap + bottomPad).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildTargets()
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacks(repeatRunnable)
        super.onDetachedFromWindow()
    }

    // ---------- public state ----------

    fun setShift(state: ShiftState) {
        shiftState = state
        invalidate()
    }

    fun setSymbols(on: Boolean) {
        if (showingSymbols == on) return
        showingSymbols = on
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
        val cy = toolbarH / 2f

        // Left toolbar button: menu (grid) or close
        val leftCx = dp(28f)
        targets.add(
            Target(
                RectF(leftCx - dp(22f), cy - dp(22f), leftCx + dp(22f), cy + dp(22f)),
                action = if (trayOpen) ToolbarAction.CLOSE else ToolbarAction.MENU,
            ),
        )

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

        // Keys
        val rows = if (showingSymbols) Layouts.symbols else Layouts.letters
        val avail = w - 2 * sideMargin
        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (avail - keyGap * (row.size - 1)) / total
            var x = sideMargin
            val top = toolbarH + topPad + r * (rowH + rowGap)
            for (key in row) {
                val kw = key.weight * unit
                if (key.type != KeyType.SPACER) {
                    targets.add(Target(RectF(x, top, x + kw, top + rowH), key = key))
                }
                x += kw + keyGap
            }
        }
    }

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

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(t.toolbarRadiusDp)

        drawTopRounded(canvas, h, w, r, t.base)
        drawTopRounded(canvas, toolbarH, w, r, t.toolbar)

        if (sensitive && !trayOpen) {
            drawLabel(canvas, "Private field", w / 2f, toolbarH / 2f, sp(14f), t.textDim)
        }

        for (tg in targets) {
            val key = tg.key
            val action = tg.action
            when {
                tg.suggestionIndex >= 0 -> drawChip(canvas, tg, t)
                action != null -> drawToolbarButton(canvas, tg, action, t)
                key != null -> drawKey(canvas, tg, key, t)
            }
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

    private fun drawKey(canvas: Canvas, tg: Target, key: Key, t: KeyboardTheme) {
        val r = tg.rect
        val pressed = isPressed(tg)
        val cx = r.centerX()
        val cy = r.centerY()

        if (key.type == KeyType.ENTER) {
            // Round bubble, inverted colors
            val radius = min(r.width(), r.height()) / 2f
            fill.color = if (pressed) ColorUtils.blendARGB(t.inverted, Color.GRAY, 0.25f) else t.inverted
            canvas.drawCircle(cx, cy, radius, fill)
            drawIcon(canvas, R.drawable.ic_enter, cx, cy, dp(24f), t.onInverted)
            return
        }

        val capsOn = key.type == KeyType.SHIFT && shiftState == ShiftState.CAPS
        val base = when {
            capsOn -> t.inverted
            key.type == KeyType.SHIFT && shiftState == ShiftState.ONE_SHOT -> t.key
            key.type == KeyType.CHAR || key.type == KeyType.SPACE -> t.key
            else -> t.specialKey
        }
        fill.color = if (pressed) ColorUtils.blendARGB(base, Color.WHITE, 0.18f) else base
        canvas.drawRoundRect(r, dp(t.keyRadiusDp), dp(t.keyRadiusDp), fill)

        val iconColor = if (capsOn) t.onInverted else t.icon
        when (key.type) {
            KeyType.SHIFT -> drawIcon(canvas, R.drawable.ic_shift, cx, cy, dp(24f), iconColor)
            KeyType.BACKSPACE -> drawIcon(canvas, R.drawable.ic_backspace, cx, cy, dp(24f), t.icon)
            KeyType.GLOBE -> drawIcon(canvas, R.drawable.ic_globe, cx, cy, dp(24f), t.icon)
            KeyType.CHAR -> {
                val label = if (!showingSymbols && shiftState != ShiftState.OFF) {
                    key.label.uppercase()
                } else {
                    key.label
                }
                drawLabel(canvas, label, cx, cy, sp(20f), t.text)
            }
            KeyType.SYMBOLS, KeyType.LETTERS -> drawLabel(canvas, key.label, cx, cy, sp(14f), t.text)
            KeyType.SPACE -> drawLabel(canvas, key.label, cx, cy, sp(14f), t.textDim)
            else -> Unit
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.textSize = size
        textPaint.color = color
        val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, cx, y, textPaint)
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
                val tg = hit(event.getX(i), event.getY(i))
                if (tg != null) {
                    pressedByPointer[event.getPointerId(i)] = tg
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (tg.key?.type == KeyType.BACKSPACE) {
                        listener?.onBackspace()
                        uiHandler.postDelayed(repeatRunnable, 380L)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val down = pressedByPointer.remove(event.getPointerId(i))
                if (down != null) {
                    if (down.key?.type == KeyType.BACKSPACE) {
                        uiHandler.removeCallbacks(repeatRunnable)
                    } else {
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
                uiHandler.removeCallbacks(repeatRunnable)
                invalidate()
            }
        }
        return true
    }

    private fun activate(tg: Target) {
        val l = listener ?: return
        if (tg.suggestionIndex >= 0) {
            val word = suggestions.getOrNull(tg.suggestionIndex)
            if (word != null) l.onSuggestion(word)
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
                val out = if (!showingSymbols && shiftState != ShiftState.OFF) key.label.uppercase() else key.label
                l.onText(out)
                if (shiftState == ShiftState.ONE_SHOT) setShift(ShiftState.OFF)
            }
            KeyType.SHIFT -> toggleShift()
            KeyType.SYMBOLS -> setSymbols(true)
            KeyType.LETTERS -> setSymbols(false)
            KeyType.GLOBE -> l.onGlobe()
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

package com.remino.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.graphics.ColorUtils

/**
 * Two small PopupWindows above the key bed:
 *
 *  - the letter preview bubble (always shown for letter keys when enabled)
 *  - the long-press alternates row for accented letters and punctuation
 *
 * Both are lightweight custom canvases, so the key bed itself never redraws
 * while a finger is sliding through alternates.
 */
class KeyPopup(private val host: View) {

    var theme: KeyboardTheme = KeyboardTheme.Dark

    private var previewWindow: PopupWindow? = null
    private var previewView: PreviewView? = null
    private var altWindow: PopupWindow? = null
    private var altView: AltView? = null

    private var chars: List<String> = emptyList()
    private var selected = 0
    private var altLeft = 0f
    private var cellWidth = 0f

    val alternatesShowing: Boolean get() = altWindow?.isShowing == true

    fun showPreview(key: Key, rect: RectF, label: String) {
        hideAlternates()
        val view = previewView ?: PreviewView(host.context).also { previewView = it }
        view.theme = theme
        view.label = label
        val popup = previewWindow ?: newWindow(view).also { previewWindow = it }
        val gap = dp(6f)
        val w = maxOf(rect.width() + dp(16f), dp(42f))
        val h = dp(54f)
        showAt(popup, view, rect.centerX() - w / 2f, rect.top - h - gap, w.toInt(), h.toInt())
    }

    fun hidePreview() {
        previewWindow?.dismiss()
    }

    fun showAlternates(rect: RectF, values: List<String>, index: Int) {
        hidePreview()
        if (values.isEmpty()) return
        chars = values
        selected = index.coerceIn(0, values.lastIndex)
        val view = altView ?: AltView(host.context).also { altView = it }
        view.theme = theme
        view.chars = chars
        view.selected = selected
        view.invalidate()
        val popup = altWindow ?: newWindow(view).also { altWindow = it }
        val cw = dp(44f)
        cellWidth = cw
        val w = cw * chars.size + dp(8f)
        val h = dp(54f)
        val left = rect.centerX() - w / 2f
        altLeft = left
        view.setSize(w, h, cw)
        showAt(popup, view, left, rect.top - h - dp(6f), w.toInt(), h.toInt())
    }

    /** Finger slide while the alternates row is open. Returns the selected character. */
    fun dragAlternates(x: Float): String? {
        if (chars.isEmpty() || !alternatesShowing) return null
        val i = ((x - altLeft - dp(4f)) / cellWidth).toInt().coerceIn(0, chars.lastIndex)
        if (i != selected) {
            selected = i
            altView?.selected = i
            altView?.invalidate()
        }
        return chars[selected]
    }

    fun selectedAlternate(): String? = chars.getOrNull(selected)

    fun hideAlternates() {
        altWindow?.dismiss()
        chars = emptyList()
    }

    fun dismissAll() {
        hidePreview()
        hideAlternates()
    }

    private fun newWindow(view: View): PopupWindow =
        PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = false
            isFocusable = false
            elevation = dp(10f)
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            animationStyle = 0
        }

    private fun showAt(popup: PopupWindow, content: View, x: Float, y: Float, w: Int, h: Int) {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        content.layout(0, 0, w, h)
        popup.width = w
        popup.height = h
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        val dm = host.resources.displayMetrics
        val screenX = (loc[0] + x).toInt().coerceIn(0, maxOf(0, dm.widthPixels - w))
        val screenY = (loc[1] + y).toInt().coerceAtLeast(0)
        try {
            popup.showAtLocation(host, Gravity.NO_GRAVITY, screenX, screenY)
        } catch (e: Exception) {
            // Some IME windows reject popups; the key bed still works without them.
        }
    }

    private fun dp(v: Float): Float = v * host.resources.displayMetrics.density

    private inner class PreviewView(context: Context) : View(context) {
        var theme = KeyboardTheme.Dark
        var label = ""
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = dp(10f)
            paint.color = ColorUtils.setAlphaComponent(Color.BLACK, 30)
            rect.set(dp(1f), dp(2f), w - dp(1f), h - dp(1f))
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = theme.key
            rect.set(dp(1f), 0f, w - dp(1f), h - dp(2f))
            canvas.drawRoundRect(rect, r, r, paint)
            text.color = theme.text
            text.textSize = sp(28f)
            val y = h / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(label, w / 2f, y, text)
        }
    }

    private inner class AltView(context: Context) : View(context) {
        var theme = KeyboardTheme.Dark
        var chars: List<String> = emptyList()
        var selected = 0
        private var cw = dp(44f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val rect = RectF()

        fun setSize(w: Float, h: Float, cell: Float) {
            cw = cell
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (chars.isEmpty()) return
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = ColorUtils.setAlphaComponent(Color.BLACK, 34)
            rect.set(dp(1f), dp(3f), w - dp(1f), h - dp(1f))
            canvas.drawRoundRect(rect, dp(10f), dp(10f), paint)
            paint.color = theme.key
            rect.set(dp(1f), 0f, w - dp(1f), h - dp(2f))
            canvas.drawRoundRect(rect, dp(10f), dp(10f), paint)

            text.textSize = sp(20f)
            chars.forEachIndexed { i, ch ->
                val left = dp(4f) + i * cw
                if (i == selected) {
                    paint.color = theme.accent
                    rect.set(left + dp(2f), dp(5f), left + cw - dp(2f), h - dp(7f))
                    canvas.drawRoundRect(rect, dp(8f), dp(8f), paint)
                }
                text.color = if (i == selected) theme.onAccent else theme.text
                val cx = left + cw / 2f
                val y = h / 2f - (text.descent() + text.ascent()) / 2f
                canvas.drawText(ch, cx, y, text)
            }
        }
    }

    private fun sp(v: Float): Float = v * host.resources.displayMetrics.scaledDensity
}

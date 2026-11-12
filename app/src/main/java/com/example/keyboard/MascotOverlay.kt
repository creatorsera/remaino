package com.example.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.sin

/**
 * A small click-through window that draws the mascot next to the real caret in any app.
 * Needs "display over other apps". Without the permission nothing is drawn and the
 * keyboard keeps the mascot in the band above the keys.
 */
class MascotOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = KeyboardPrefs(context)
    private var view: MascotView? = null
    private var params: WindowManager.LayoutParams? = null

    val sizePx: Int
        get() = (64 * context.resources.displayMetrics.density *
            (prefs.mascotScale.coerceIn(70, 140) / 100f)).toInt()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show(rawX: Int, rawY: Int, deleting: Boolean) {
        if (!canShow()) return
        // Keep the whole little window inside the visible area, so it never slides
        // under the status bar, navigation bar or tablet taskbar.
        val safe = safeBounds()
        val x = rawX.coerceIn(safe.left, max(safe.left, safe.right - sizePx))
        val y = rawY.coerceIn(safe.top, max(safe.top, safe.bottom - sizePx))
        val existing = view
        val existingParams = params
        if (existing != null && existingParams != null) {
            existingParams.x = x
            existingParams.y = y
            existingParams.width = sizePx
            existingParams.height = sizePx
            existing.deleting = deleting
            existing.mascotColor = prefs.mascotColor
            try {
                windowManager.updateViewLayout(existing, existingParams)
            } catch (e: Exception) {
                hide()
            }
            return
        }
        val v = MascotView(context)
        v.deleting = deleting
        v.mascotColor = prefs.mascotColor
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        try {
            windowManager.addView(v, lp)
            view = v
            params = lp
        } catch (e: Exception) {
            view = null
            params = null
        }
    }

    fun hide() {
        val v = view ?: return
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            // Already gone.
        }
        view = null
        params = null
    }

    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val b = metrics.bounds
            return Rect(
                b.left + insets.left,
                b.top + insets.top,
                b.right - insets.right,
                b.bottom - insets.bottom,
            )
        }
        val metrics = context.resources.displayMetrics
        val res = context.resources
        val statusId = res.getIdentifier("status_bar_height", "dimen", "android")
        val navId = res.getIdentifier("navigation_bar_height", "dimen", "android")
        val status = if (statusId > 0) res.getDimensionPixelSize(statusId) else 0
        val nav = if (navId > 0) res.getDimensionPixelSize(navId) else 0
        return Rect(0, status, metrics.widthPixels, metrics.heightPixels - nav)
    }
}

private class MascotView(context: Context) : View(context) {
    var deleting = false
    var mascotColor = MascotColor.LIME

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmp = RectF()

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val r = width * 0.19f
        val cx = width / 2f
        val cy = height / 2f
        val walk = if (deleting) sin(now / 70.0).toFloat() * r * 0.18f else 0f
        MascotArt.draw(
            canvas = canvas,
            fill = fill,
            stroke = stroke,
            tmp = tmp,
            cx = cx,
            cy = cy,
            r = r,
            body = mascotColor.body,
            onBody = mascotColor.ink,
            now = now,
            wave = !deleting,
            pushToX = null,
            deleting = deleting,
            walk = walk,
        )
        postInvalidateOnAnimation()
    }
}

package com.example.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.sin

/**
 * A small click-through window that draws the mascot next to the real caret in any app.
 * Needs "display over other apps". Without the permission nothing is drawn and the
 * keyboard keeps the mascot in the band above the keys.
 */
class MascotOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: MascotView? = null
    private var params: WindowManager.LayoutParams? = null

    val sizePx: Int get() = (64 * context.resources.displayMetrics.density).toInt()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show(x: Int, y: Int, deleting: Boolean) {
        if (!canShow()) return
        val existing = view
        val existingParams = params
        if (existing != null && existingParams != null) {
            existingParams.x = x
            existingParams.y = y
            existing.deleting = deleting
            try {
                windowManager.updateViewLayout(existing, existingParams)
            } catch (e: Exception) {
                hide()
            }
            return
        }
        val v = MascotView(context)
        v.deleting = deleting
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
}

private class MascotView(context: Context) : View(context) {
    var deleting = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmp = RectF()

    override fun onDraw(canvas: Canvas) {
        val t = KeyboardTheme.Dark
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
            body = t.accent,
            onBody = t.onAccent,
            now = now,
            wave = !deleting,
            pushToX = null,
            deleting = deleting,
            walk = walk,
        )
        postInvalidateOnAnimation()
    }
}

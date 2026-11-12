package com.remino.keyboard

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Puts the keyboard in a movable window drawn over other apps. The real keyboard window stays
 * a one-pixel strip so the app underneath is not pushed up. Drag the small bar at the top to move it.
 * Some apps hide overlay windows; in those apps switch the floating keyboard off.
 */
class FloatingHost(private val context: Context, private val prefs: KeyboardPrefs) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var frame: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = frame != null

    /** Left edge of the floating window, used to line the mascot up with the caret. */
    val offsetX: Int get() = params?.x ?: 0

    fun canUse(): Boolean = Settings.canDrawOverlays(context)

    fun show(keyboard: KeyboardView): Boolean {
        if (frame != null) return true
        val dm = context.resources.displayMetrics
        val w = min((dm.widthPixels * 0.94f).toInt(), (460 * dm.density).toInt())
        val lp = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val savedX = prefs.floatingX
        val savedY = prefs.floatingY
        lp.x = if (savedX >= 0) savedX.coerceAtMost(max(0, dm.widthPixels - w)) else (dm.widthPixels - w) / 2
        lp.y = if (savedY >= 0) savedY else (dm.heightPixels * 0.45f).toInt()

        (keyboard.parent as? ViewGroup)?.removeView(keyboard)
        val f = FrameLayout(context)
        f.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return try {
            windowManager.addView(f, lp)
            frame = f
            params = lp
            true
        } catch (e: Exception) {
            f.removeAllViews()
            false
        }
    }

    fun move(dx: Float, dy: Float) {
        val f = frame ?: return
        val lp = params ?: return
        val dm = context.resources.displayMetrics
        lp.x = (lp.x + dx).toInt().coerceIn(0, max(0, dm.widthPixels - lp.width))
        lp.y = (lp.y + dy).toInt().coerceIn(0, max(0, dm.heightPixels - f.height))
        try {
            windowManager.updateViewLayout(f, lp)
        } catch (e: Exception) {
            // The window was removed while dragging.
        }
    }

    fun savePosition() {
        val lp = params ?: return
        prefs.floatingX = lp.x
        prefs.floatingY = lp.y
    }

    /** Removes the window and detaches the keyboard so it can go back into the normal keyboard window. */
    fun hide(keyboard: KeyboardView) {
        val f = frame ?: return
        f.removeView(keyboard)
        try {
            windowManager.removeView(f)
        } catch (e: Exception) {
            // Already gone.
        }
        frame = null
        params = null
    }
}

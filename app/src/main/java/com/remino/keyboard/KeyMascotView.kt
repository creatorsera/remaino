package com.remino.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/**
 * A 34dp circular mascot. The blink is a short ValueAnimator scheduled on idle;
 * only this small view is invalidated, never the key bed.
 */
class KeyMascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var body = MascotColor.LIME.body
    private var ink = MascotColor.LIME.ink
    private var accent = KeyboardTheme.Dark.accent
    private var listening = false
    private var blinking = false
    private var blinkValue = 0f
    private var smileUntil = 0L
    private var blinkAnimator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Reo, quick settings"
    }

    fun setColors(body: Int, ink: Int, accent: Int) {
        this.body = body
        this.ink = ink
        this.accent = accent
        invalidate()
    }

    fun setListening(on: Boolean) {
        if (listening == on) return
        listening = on
        invalidate()
    }

    /** Brief happy face after a key press. */
    fun poke() {
        smileUntil = SystemClock.uptimeMillis() + 700L
        invalidate()
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleBlink()
    }

    override fun onDetachedFromWindow() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    private val blinkRunnable = Runnable { blink() }

    private fun scheduleBlink() {
        removeCallbacks(blinkRunnable)
        postDelayed(blinkRunnable, 3200L + (SystemClock.uptimeMillis() % 2200L))
    }

    private fun blink() {
        if (!isAttachedToWindow) return
        blinkAnimator?.cancel()
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 180L
            addUpdateListener {
                blinkValue = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = scheduleBlink()
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - dp(1f)
        val now = SystemClock.uptimeMillis()
        val smiling = now < smileUntil

        if (listening) {
            stroke.color = accent
            stroke.strokeWidth = dp(2f)
            canvas.drawCircle(cx, cy, r + dp(1f), stroke)
        }

        fill.color = body
        canvas.drawCircle(cx, cy, r, fill)

        // A soft highlight keeps the token readable without a gradient shader.
        fill.color = ColorUtils.setAlphaComponent(Color.WHITE, 70)
        rect.set(cx - r * 0.52f, cy - r * 0.58f, cx + r * 0.12f, cy - r * 0.18f)
        canvas.drawOval(rect, fill)

        val eyeY = cy - r * 0.18f
        val eyeDx = r * 0.30f
        val eyeR = r * 0.10f
        fill.color = ink
        if (blinkValue > 0.55f) {
            stroke.color = ink
            stroke.strokeWidth = dp(1.7f)
            canvas.drawLine(cx - eyeDx - eyeR, eyeY, cx - eyeDx + eyeR, eyeY, stroke)
            canvas.drawLine(cx + eyeDx - eyeR, eyeY, cx + eyeDx + eyeR, eyeY, stroke)
        } else {
            canvas.drawCircle(cx - eyeDx, eyeY, eyeR, fill)
            canvas.drawCircle(cx + eyeDx, eyeY, eyeR, fill)
        }

        stroke.color = ink
        stroke.strokeWidth = dp(1.6f)
        rect.set(cx - r * 0.36f, cy + r * 0.02f, cx + r * 0.36f, cy + r * 0.42f)
        canvas.drawArc(rect, if (smiling) 15f else 25f, if (smiling) 150f else 130f, false, stroke)

        if (smiling) postInvalidateOnAnimation()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

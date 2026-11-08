package com.example.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.sin

/**
 * Draws the Remino companion. One implementation is shared by the toolbar lane,
 * the band above the keys, the at-cursor overlay and the Settings preview, so the
 * little friend looks and moves the same everywhere.
 */
object MascotArt {

    /**
     * @param r body radius in pixels.
     * @param now milliseconds, used for blinking, waving and wobbling.
     * @param wave true when the right arm should say hi.
     * @param pushToX when not null, both arms reach to this x to push the caret.
     * @param deleting shows a focused face, a slight squash and walking feet.
     * @param walk small left/right shuffle of the feet.
     */
    fun draw(
        canvas: Canvas,
        fill: Paint,
        stroke: Paint,
        tmp: RectF,
        cx: Float,
        cy: Float,
        r: Float,
        body: Int,
        onBody: Int,
        now: Long,
        wave: Boolean,
        pushToX: Float?,
        deleting: Boolean,
        walk: Float,
    ) {
        val bob = sin(now / 430.0).toFloat() * r * 0.07f
        val cyb = cy + bob

        // Soft glow so the little one feels lit from inside.
        fill.color = withAlpha(body, 26)
        canvas.drawCircle(cx, cyb, r * 1.42f, fill)

        // Contact shadow on the "floor".
        fill.color = withAlpha(onBody, 46)
        tmp.set(cx - r * 0.85f, cyb + r * 0.98f, cx + r * 0.85f, cyb + r * 1.26f)
        canvas.drawOval(tmp, fill)

        // Antenna first, so it sits behind the head.
        val wobble = sin(now / 320.0).toFloat() * r * 0.07f
        stroke.color = body
        stroke.strokeWidth = r * 0.13f
        canvas.drawLine(cx, cyb - r * 0.88f, cx + r * 0.10f + wobble, cyb - r * 1.30f, stroke)
        fill.color = withAlpha(body, 76)
        canvas.drawCircle(cx + r * 0.10f + wobble, cyb - r * 1.36f, r * 0.26f, fill)
        fill.color = body
        canvas.drawCircle(cx + r * 0.10f + wobble, cyb - r * 1.36f, r * 0.15f, fill)

        // Feet behind the body.
        fill.color = body
        val step = if (deleting) walk else 0f
        tmp.set(
            cx - r * 0.52f + step, cyb + r * 0.82f,
            cx - r * 0.12f + step, cyb + r * 1.10f,
        )
        canvas.drawRoundRect(tmp, r * 0.16f, r * 0.16f, fill)
        tmp.set(
            cx + r * 0.12f - step, cyb + r * 0.82f,
            cx + r * 0.52f - step, cyb + r * 1.10f,
        )
        canvas.drawRoundRect(tmp, r * 0.16f, r * 0.16f, fill)

        // Body: a slightly pear-shaped blob rather than a plain circle.
        fill.color = body
        tmp.set(cx - r, cyb - r * 0.94f, cx + r, cyb + r * 1.04f)
        canvas.drawOval(tmp, fill)

        // Sheen across the top-left shoulder.
        fill.color = Color.WHITE
        fill.alpha = 30
        tmp.set(cx - r * 0.58f, cyb - r * 0.76f, cx + r * 0.08f, cyb - r * 0.12f)
        canvas.drawOval(tmp, fill)
        fill.alpha = 255

        // Arms.
        stroke.color = body
        stroke.strokeWidth = r * 0.17f
        if (pushToX != null) {
            // Both hands shove the caret back.
            val reach = pushToX.coerceAtMost(cx - r * 0.4f)
            canvas.drawLine(cx - r * 0.72f, cyb - r * 0.16f, reach, cyb - r * 0.20f, stroke)
            canvas.drawLine(cx - r * 0.62f, cyb + r * 0.30f, reach, cyb + r * 0.34f, stroke)
            fill.color = body
            canvas.drawCircle(reach, cyb - r * 0.20f, r * 0.17f, fill)
            canvas.drawCircle(reach, cyb + r * 0.34f, r * 0.17f, fill)
        } else if (wave) {
            // Right arm up, saying hi with a slow wind-up.
            val swing = sin(now / 160.0).toFloat() * r * 0.22f
            val handX = cx + r * 1.32f
            val handY = cyb - r * 0.92f + swing
            canvas.drawLine(cx + r * 0.74f, cyb + r * 0.02f, handX, handY, stroke)
            fill.color = body
            canvas.drawCircle(handX, handY, r * 0.19f, fill)
            // Left arm rests.
            canvas.drawLine(cx - r * 0.74f, cyb + r * 0.06f, cx - r * 0.90f, cyb + r * 0.44f, stroke)
        } else {
            // Relaxed arms at the sides.
            canvas.drawLine(cx - r * 0.74f, cyb + r * 0.04f, cx - r * 0.92f, cyb + r * 0.42f, stroke)
            canvas.drawLine(cx + r * 0.74f, cyb + r * 0.04f, cx + r * 0.92f, cyb + r * 0.42f, stroke)
        }

        // Cheeks.
        fill.color = withAlpha(onBody, 58)
        canvas.drawCircle(cx - r * 0.52f, cyb + r * 0.28f, r * 0.13f, fill)
        canvas.drawCircle(cx + r * 0.52f, cyb + r * 0.28f, r * 0.13f, fill)

        // Eyes: blink for a short moment every few seconds.
        val blinkPhase = now % 4200L
        val eyeOpen = if (blinkPhase in 0L..115L) 0.12f else 1f
        val eyeY = cyb - r * 0.14f
        val eyeDX = r * 0.33f
        val eyeW = r * 0.19f
        val eyeH = r * 0.25f * eyeOpen
        fill.color = onBody
        tmp.set(cx - eyeDX - eyeW, eyeY - eyeH, cx - eyeDX + eyeW, eyeY + eyeH)
        canvas.drawOval(tmp, fill)
        tmp.set(cx + eyeDX - eyeW, eyeY - eyeH, cx + eyeDX + eyeW, eyeY + eyeH)
        canvas.drawOval(tmp, fill)
        if (eyeOpen > 0.6f) {
            fill.color = body
            canvas.drawCircle(cx - eyeDX + eyeW * 0.3f, eyeY - eyeH * 0.35f, r * 0.055f, fill)
            canvas.drawCircle(cx + eyeDX + eyeW * 0.3f, eyeY - eyeH * 0.35f, r * 0.055f, fill)
        }

        // Mouth: a small smile, or a focused "o" while deleting.
        stroke.color = onBody
        stroke.strokeWidth = r * 0.12f
        if (deleting) {
            tmp.set(cx - r * 0.11f, cyb + r * 0.26f, cx + r * 0.11f, cyb + r * 0.50f)
            canvas.drawOval(tmp, stroke)
        } else {
            tmp.set(cx - r * 0.25f, cyb + r * 0.16f, cx + r * 0.25f, cyb + r * 0.52f)
            canvas.drawArc(tmp, 18f, 144f, false, stroke)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}

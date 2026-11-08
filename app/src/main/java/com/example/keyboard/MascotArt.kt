package com.example.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Draws the round mascot. Used by the at-cursor overlay window. */
object MascotArt {
    /**
     * @param r body radius in pixels
     * @param pushToX when not null, one arm reaches to this x (pushing the caret back)
     * @param waveHand when not null, the other arm is raised and moved by this many pixels (saying hi)
     * @param walk small left and right shuffle of the feet while moving
     */
    fun draw(
        canvas: Canvas,
        fill: Paint,
        stroke: Paint,
        tmp: RectF,
        bx: Float,
        by: Float,
        r: Float,
        body: Int,
        onBody: Int,
        pushToX: Float?,
        waveHand: Float?,
        walk: Float,
    ) {
        stroke.color = body
        stroke.strokeWidth = r * 0.23f
        if (pushToX != null) canvas.drawLine(bx - r * 0.82f, by + r * 0.18f, pushToX, by, stroke)
        fill.color = body
        canvas.drawCircle(bx, by, r, fill)
        if (waveHand != null) {
            canvas.drawLine(bx + r * 0.85f, by + r * 0.1f, bx + r * 1.45f, by - r * 0.55f + waveHand, stroke)
        }
        stroke.strokeWidth = r * 0.15f
        canvas.drawLine(bx, by - r, bx + r * 0.18f, by - r * 1.36f, stroke)
        canvas.drawCircle(bx + r * 0.32f, by - r * 1.36f, r * 0.2f, fill)
        canvas.drawCircle(bx - r * 0.36f + walk, by + r, r * 0.24f, fill)
        canvas.drawCircle(bx + r * 0.36f - walk, by + r, r * 0.24f, fill)
        fill.color = onBody
        canvas.drawCircle(bx - r * 0.35f, by - r * 0.18f, r * 0.15f, fill)
        canvas.drawCircle(bx + r * 0.35f, by - r * 0.18f, r * 0.15f, fill)
        stroke.color = onBody
        stroke.strokeWidth = r * 0.13f
        tmp.set(bx - r * 0.27f, by + r * 0.05f, bx + r * 0.27f, by + r * 0.5f)
        canvas.drawArc(tmp, 20f, 140f, false, stroke)
    }
}

package com.example.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/** Turns typed text into a rounded sticker image (PNG with transparent corners). */
object StickerMaker {
    /** Pairs of (background, text) colors. */
    val palettes: List<Pair<Int, Int>> = listOf(
        0xFFF2F2F5.toInt() to 0xFF121214.toInt(),
        0xFF2B2B31.toInt() to 0xFFF2F2F5.toInt(),
        0xFFFFD166.toInt() to 0xFF1C1C20.toInt(),
        0xFF9BF6FF.toInt() to 0xFF1C1C20.toInt(),
        0xFFFFADAD.toInt() to 0xFF1C1C20.toInt(),
        0xFFCAFFBF.toInt() to 0xFF1C1C20.toInt(),
    )

    fun render(text: String, paletteIndex: Int): Bitmap {
        val colors = palettes[paletteIndex.coerceIn(0, palettes.size - 1)]
        val width = 512
        val textWidth = 400
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.second
            typeface = Typeface.DEFAULT_BOLD
        }
        var size = 120f
        var layout = buildLayout(text, paint, size, textWidth)
        while (layout.height > 400 && size > 28f) {
            size -= 6f
            layout = buildLayout(text, paint, size, textWidth)
        }
        val height = (layout.height + 112).coerceAtLeast(200)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.first }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 96f, 96f, background)
        canvas.translate((width - textWidth) / 2f, (height - layout.height) / 2f)
        layout.draw(canvas)
        return bitmap
    }

    private fun buildLayout(text: String, paint: TextPaint, size: Float, width: Int): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
    }

    fun save(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "stickers")
        dir.mkdirs()
        val old = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        if (old.size > 20) old.take(old.size - 20).forEach { it.delete() }
        val file = File(dir, "sticker_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }
}

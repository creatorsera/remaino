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
        0xFFC8F26A.toInt() to 0xFF121214.toInt(),
        0xFFB9A0FF.toInt() to 0xFF121214.toInt(),
        0xFF8FD3FF.toInt() to 0xFF121214.toInt(),
        0xFF8EF0C0.toInt() to 0xFF121214.toInt(),
        0xFFFFA8D5.toInt() to 0xFF121214.toInt(),
        0xFF1F1F24.toInt() to 0xFFC8F26A.toInt(),
    )

    /** Names shown in the sticker toolbar picker. */
    val paletteNames: List<String> = listOf(
        "Paper", "Ink", "Honey", "Ice", "Blush", "Mint",
        "Lime", "Violet", "Sky", "Sage", "Rose", "Night",
    )

    /** Corner treatment for the sticker. */
    enum class Shape { ROUNDED, SQUIRCLE, PILL, SLAB }

    /** Fonts and shapes that keep the stickers looking hand-made, not templated. */
    data class Style(
        val label: String,
        val shape: Shape,
        val typeface: Typeface,
    )

    val styles: List<Style> = listOf(
        Style("Bold", Shape.ROUNDED, Typeface.DEFAULT_BOLD),
        Style("Soft", Shape.SQUIRCLE, Typeface.create("sans-serif-medium", Typeface.NORMAL)),
        Style("Wide", Shape.PILL, Typeface.create("sans-serif-black", Typeface.NORMAL)),
        Style("Serif", Shape.ROUNDED, Typeface.create(Typeface.SERIF, Typeface.BOLD)),
        Style("Mono", Shape.SLAB, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)),
        Style("Italic", Shape.SQUIRCLE, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)),
    )

    fun render(text: String, paletteIndex: Int, styleIndex: Int = 0): Bitmap {
        val colors = palettes[paletteIndex.coerceIn(0, palettes.size - 1)]
        val look = styles[styleIndex.coerceIn(0, styles.size - 1)]
        val width = 512
        val textWidth = 400
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.second
            typeface = look.typeface
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

        val shapeRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = when (look.shape) {
            Shape.ROUNDED -> 96f
            Shape.SQUIRCLE -> 150f
            Shape.PILL -> height / 2f
            Shape.SLAB -> 26f
        }
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.first }
        canvas.drawRoundRect(shapeRect, radius, radius, background)

        // A thin inner line adds depth without a heavy shadow.
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = (colors.second and 0x00FFFFFF) or 0x1A000000
        }
        val inset = RectF(12f, 12f, width - 12f, height - 12f)
        val innerRadius = (radius - 12f).coerceAtLeast(8f)
        canvas.drawRoundRect(inset, innerRadius, innerRadius, inner)

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

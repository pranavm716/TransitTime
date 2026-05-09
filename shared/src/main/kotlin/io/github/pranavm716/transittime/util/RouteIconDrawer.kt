package io.github.pranavm716.transittime.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap

object RouteIconDrawer {

    fun draw(style: RouteStyle, text: String, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.backgroundColor
            this.style = Paint.Style.FILL
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textColor
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val baseSize = when (text.length) {
            1 -> sizePx * 0.45f
            2 -> sizePx * 0.40f
            else -> sizePx * 0.38f
        }
        textPaint.textSize = baseSize

        val maxWidth = sizePx * 0.85f
        val measuredWidth = textPaint.measureText(text)
        if (measuredWidth > maxWidth) {
            textPaint.textSize = baseSize * (maxWidth / measuredWidth)
        }

        val bounds = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())

        when (style.shape) {
            RouteShape.SQUARE -> {
                val radius = sizePx * 0.08f
                canvas.drawRoundRect(bounds, radius, radius, bgPaint)
            }

            RouteShape.CIRCLE -> {
                canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bgPaint)
            }

            RouteShape.ROUNDED_RECT -> {
                val radius = sizePx * 0.35f
                canvas.drawRoundRect(bounds, radius, radius, bgPaint)
            }

            RouteShape.RECT -> {
                val vPad = sizePx * 0.2f
                val rectBounds = RectF(0f, vPad, sizePx.toFloat(), sizePx.toFloat() - vPad)
                canvas.drawRect(rectBounds, bgPaint)
            }
        }

        val cx = sizePx / 2f
        val cy = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, cx, cy, textPaint)

        return bitmap
    }
}

package io.github.pranavm716.transittime.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import io.github.pranavm716.transittime.data.model.Agency

object RouteIconDrawer {

    fun draw(agency: Agency, routeName: String, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val routeStyle = RouteColors.getStyle(agency, routeName)  // renamed

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = routeStyle.backgroundColor
            style = Paint.Style.FILL  // now unambiguous
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = routeStyle.textColor
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.45f
        }

        val bounds = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())

        when (routeStyle.shape) {
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
        }

        val label = getLabel(agency, routeName)
        val cx = sizePx / 2f
        val cy = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, cy, textPaint)

        return bitmap
    }

    // For bus routes with wide labels (e.g. "38R"), draw a wider bitmap
    fun drawWide(agency: Agency, routeName: String, heightPx: Int): Bitmap {
        val label = getLabel(agency, routeName)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = heightPx * 0.45f
        }

        val style = RouteColors.getStyle(agency, routeName)
        val textWidth = textPaint.measureText(label)
        val widthPx = (textWidth + heightPx * 0.6f).toInt()

        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.backgroundColor
            this.style = Paint.Style.FILL
        }

        val radius = heightPx * 0.35f
        canvas.drawRoundRect(
            RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
            radius, radius, bgPaint
        )

        textPaint.color = style.textColor
        val cx = widthPx / 2f
        val cy = heightPx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, cy, textPaint)

        return bitmap
    }

    fun getLabel(agency: Agency, routeName: String): String {
        return when (agency) {
            Agency.BART -> when {
                routeName.contains("Red", ignoreCase = true) -> "R"
                routeName.contains("Yellow", ignoreCase = true) -> "Y"
                routeName.contains("Blue", ignoreCase = true) -> "B"
                routeName.contains("Green", ignoreCase = true) -> "G"
                routeName.contains("Orange", ignoreCase = true) -> "O"
                else -> "?"
            }

            Agency.MUNI -> routeName.uppercase()
        }
    }
}
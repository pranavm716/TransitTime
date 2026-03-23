package io.github.pranavm716.transittime.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import io.github.pranavm716.transittime.data.model.Agency

object RouteIconDrawer {

    fun draw(agency: Agency, routeName: String, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val routeStyle = RouteColors.getStyle(agency, routeName)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = routeStyle.backgroundColor
            style = Paint.Style.FILL
        }

        val label = getLabel(agency, routeName)
        val textSize = when (label.length) {
            1 -> sizePx * 0.45f
            2 -> sizePx * 0.40f
            else -> sizePx * 0.32f
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = routeStyle.textColor
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            this.textSize = textSize
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

        val cx = sizePx / 2f
        val cy = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
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
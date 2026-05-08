package io.github.pranavm716.transittime.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.model.IconShape
import io.github.pranavm716.transittime.model.TileSnapshot
import io.github.pranavm716.transittime.util.RouteIconDrawer
import io.github.pranavm716.transittime.util.RouteShape
import io.github.pranavm716.transittime.util.RouteStyle

object GoModeNotificationRenderer {

    private const val CHANNEL_ID = "go_mode_channel_v2"

    fun render(
        context: Context,
        widgetId: Int,
        snapshot: TileSnapshot
    ): Notification {
        val soonestRow = snapshot.rows.firstOrNull()
        val displayTime = soonestRow?.displayTimes?.firstOrNull() ?: "—"
        val headsign = soonestRow?.headsign ?: "No departures"
        val baseColor = soonestRow?.iconBgColor ?: Color.GRAY
        val iconText = soonestRow?.iconText ?: soonestRow?.routeName ?: "?"
        val iconTextColor = soonestRow?.iconTextColor ?: Color.WHITE

        // We "poison" grayscale colors to prevent Android 16 from auto-tinting it as a template.
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)
        val isGrayscale = r == g && g == b
        val displayColor = if (isGrayscale) {
            Color.rgb(r, g, if (b < 255) b + 1 else b - 1)
        } else baseColor

        val shortText = "$displayTime • $headsign"
        val contentTitle = snapshot.stopName
        val contentText =
            if (soonestRow != null) "$displayTime • ${soonestRow.routeName} to $headsign" else "No upcoming departures"

        val toggleIntent = Intent(context, io.github.pranavm716.transittime.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            widgetId,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= 36) { // Android 16
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(drawTextIcon(iconText, iconTextColor))
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                // Set the pill background to the route color as requested
                .setColor(displayColor)
                .addExtras(Bundle().apply {
                    putCharSequence("android.shortCriticalText", shortText)
                    putBoolean("android.requestPromotedOngoing", true)
                })
                .build()
        } else {
            val styleForIcon = RouteStyle(
                backgroundColor = displayColor,
                textColor = iconTextColor,
                shape = when (soonestRow?.iconShape) {
                    IconShape.SQUARE -> RouteShape.SQUARE
                    IconShape.ROUNDED_RECT -> RouteShape.ROUNDED_RECT
                    IconShape.RECT -> RouteShape.RECT
                    else -> RouteShape.CIRCLE
                }
            )
            val icon = RouteIconDrawer.draw(styleForIcon, iconText, 96)
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ongoing_dot)
                .setLargeIcon(icon)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(displayColor)
                .build()
        }
    }

    private fun drawTextIcon(text: String, color: Int): Icon {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.8f
        }

        val maxWidth = size * 1.0f
        val measuredWidth = paint.measureText(text)
        if (measuredWidth > maxWidth) {
            paint.textSize *= (maxWidth / measuredWidth)
        }

        val x = size / 2f
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, x, y, paint)
        return Icon.createWithBitmap(bitmap)
    }
}
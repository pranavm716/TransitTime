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
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.data.model.WidgetConfig
import io.github.pranavm716.transittime.transit.AgencyRegistry
import io.github.pranavm716.transittime.util.RouteIconDrawer
import io.github.pranavm716.transittime.util.getDisplayTime
import io.github.pranavm716.transittime.widget.TransitWidget

object GoModeNotificationRenderer {

    private const val CHANNEL_ID = "go_mode_channel"

    fun render(
        context: Context,
        widgetId: Int,
        config: WidgetConfig,
        soonest: Departure,
        now: Long
    ): Notification {
        val handler = AgencyRegistry.get(config.agency)
        val routeStyle = handler.getRouteStyle(soonest.routeName)
        val iconText = handler.getIconText(soonest.routeName)

        // Draw the icon as it appears on the widget.
        // We "poison" grayscale colors to prevent Android 16 from auto-tinting it as a template.
        val baseColor = routeStyle.backgroundColor
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)
        val isGrayscale = r == g && g == b
        val displayColor = if (isGrayscale) {
            Color.rgb(r, g, if (b < 255) b + 1 else b - 1)
        } else baseColor

        val styleForIcon = routeStyle.copy(backgroundColor = displayColor)
        val icon = RouteIconDrawer.draw(styleForIcon, iconText, 96)

        val displayTime = getDisplayTime(
            arrivalTimestamp = soonest.arrivalTimestamp,
            departureTimestamp = soonest.departureTimestamp,
            isOriginStop = soonest.isOriginStop,
            isScheduled = soonest.isScheduled,
            now = now,
            displayMode = config.displayMode,
            hybridThresholdMinutes = config.hybridThresholdMinutes
        )

        val shortText = "• $displayTime • ${soonest.headsign}"
        val contentTitle = config.stopName
        val contentText = "$displayTime to ${soonest.headsign}"

        val toggleIntent = Intent(TransitWidget.ACTION_TOGGLE_GO_MODE).apply {
            setPackage(context.packageName)
            putExtra(TransitWidget.EXTRA_WIDGET_ID, widgetId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            widgetId,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= 36) { // Android 16
            Notification.Builder(context, CHANNEL_ID)
                // We "trick" the OS by making the icon just the text with no shape around it.
                // This removes the empty gap at the front while following the requested format.
                .setSmallIcon(drawTextIcon(iconText, routeStyle.textColor))
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                // Set the pill background to the route color as requested
                .setColor(baseColor)
                .addExtras(Bundle().apply {
                    putCharSequence("android.shortCriticalText", shortText)
                    putBoolean("android.requestPromotedOngoing", true)
                })
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ongoing_dot)
                .setLargeIcon(icon)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(baseColor)
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
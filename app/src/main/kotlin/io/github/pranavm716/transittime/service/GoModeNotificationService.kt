package io.github.pranavm716.transittime.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.pranavm716.transittime.GoModeManager
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.transit.AgencyRegistry
import io.github.pranavm716.transittime.util.RouteIconDrawer
import io.github.pranavm716.transittime.util.getDisplayTime
import io.github.pranavm716.transittime.widget.TransitWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GoModeNotificationService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val widgetId = intent?.getIntExtra(EXTRA_WIDGET_ID, -1) ?: -1
        if (widgetId == -1) {
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            updateNotification(widgetId)
        }

        return START_STICKY
    }

    private suspend fun updateNotification(widgetId: Int) {
        val db = TransitDatabase.getInstance(this)
        val config = db.widgetConfigDao().getConfig(widgetId) ?: return
        val departures = db.departureDao().getDeparturesForStop(config.stopId)
        val soonest = departures.firstOrNull() ?: return

        val handler = AgencyRegistry.get(config.agency)
        val routeStyle = handler.getRouteStyle(soonest.routeName)
        val iconText = handler.getIconText(soonest.routeName)
        val icon = RouteIconDrawer.draw(routeStyle, iconText, 96)

        val now = System.currentTimeMillis()
        val displayTime = getDisplayTime(
            arrivalTimestamp = soonest.arrivalTimestamp,
            departureTimestamp = soonest.departureTimestamp,
            isOriginStop = soonest.isOriginStop,
            isScheduled = soonest.isScheduled,
            now = now,
            displayMode = config.displayMode,
            hybridThresholdMinutes = config.hybridThresholdMinutes
        )

        val shortText = "$displayTime • ${soonest.headsign}"
        val contentTitle = config.stopName
        val contentText = "$displayTime to ${soonest.headsign}"

        val toggleIntent = Intent(TransitWidget.ACTION_TOGGLE_GO_MODE).apply {
            setPackage(packageName)
            putExtra(TransitWidget.EXTRA_WIDGET_ID, widgetId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            widgetId,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationColor = getColor(R.color.accent_color)
        val smallIcon = Icon.createWithBitmap(icon).apply {
            setTint(notificationColor)
        }

        val notification = if (Build.VERSION.SDK_INT >= 36) { // Android 16
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(smallIcon)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                // New Android 16 APIs (Using extras to bypass unresolved reference in older SDKs if needed)
                .setColor(notificationColor)
                .addExtras(android.os.Bundle().apply {
                    putCharSequence("android.shortCriticalText", shortText)
                    putBoolean("android.requestPromotedOngoing", true)
                })
                .build()
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ongoing_dot) // Fallback small icon
                .setLargeIcon(icon)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        }

        withContext(Dispatchers.Main) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Go Mode",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Real-time transit departures for Go Mode"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "go_mode_channel"
        private const val EXTRA_WIDGET_ID = "widget_id"

        fun update(context: Context) {
            val goModeManager = GoModeManager(context)
            val isGoModeActive = goModeManager.isGoModeActive
            val widgetId = goModeManager.goModeWidgetId

            if (!isGoModeActive || widgetId == -1) {
                context.stopService(Intent(context, GoModeNotificationService::class.java))
                return
            }

            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true

            if (hasPermission) {
                val intent = Intent(context, GoModeNotificationService::class.java).apply {
                    putExtra(EXTRA_WIDGET_ID, widgetId)
                }
                context.startForegroundService(intent)
            }
        }
    }
}

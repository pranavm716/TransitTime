package io.github.pranavm716.transittime.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import io.github.pranavm716.transittime.GoModeManager
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.wear.buildSnapshot
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

        val goModeManager = GoModeManager(this)
        val snapshot = buildSnapshot(
            config = config,
            departures = departures,
            goModeActive = goModeManager.isGoModeActive,
            goModeExpiresAt = goModeManager.goModeExpiresAt,
            goModeTarget = true // This widget is the target for the notification
        )

        val notification = GoModeNotificationRenderer.render(this, widgetId, snapshot)

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

package io.github.pranavm716.transittime.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

class GoModeNotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LiveNotif", "GoModeNotificationService.onStartCommand")

        val pendingIntent = PendingIntent.getActivity(
            this,
            888,
            Intent(this, ActionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(ActionActivity.EXTRA_ACTION, "open_app")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TransitTime")
            .setContentText("Go Mode Active")
            .setSmallIcon(R.drawable.ic_ongoing_dot)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)

        val status = Status.Builder()
            .addTemplate("Activated")
            .build()

        val ongoingActivity = OngoingActivity.Builder(
            applicationContext, NOTIFICATION_ID, builder
        )
            .setStaticIcon(R.drawable.ic_ongoing_dot)
            .setTouchIntent(pendingIntent)
            .setStatus(status)
            .build()

        ongoingActivity.apply(applicationContext)

        startForeground(NOTIFICATION_ID, builder.build())

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("LiveNotif", "GoModeNotificationService.onDestroy")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Go Mode",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "go_mode_channel_v2"
        
        @Volatile
        private var isServiceRunning = false

        fun update(context: Context) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true

            val cache = WearLocalCache(context)
            val stopIds = cache.getStopIds()
            val localOverride = cache.getLocalGoModeOverride()
            
            var goModeActive = localOverride ?: false
            if (localOverride == null) {
                for (stopId in stopIds) {
                    val snapshot = cache.getSnapshot(stopId)
                    if (snapshot?.goModeActive == true) {
                        goModeActive = true
                        break
                    }
                }
            }

            Log.d("LiveNotif", "update: active=$goModeActive, isRunning=$isServiceRunning")
            
            if (goModeActive == isServiceRunning) return

            val intent = Intent(context, GoModeNotificationService::class.java)
            if (goModeActive) {
                if (hasPermission) {
                    try {
                        context.startForegroundService(intent)
                        isServiceRunning = true
                    } catch (e: Exception) {
                        Log.e("LiveNotif", "startForegroundService failed", e)
                    }
                }
            } else {
                context.stopService(intent)
                isServiceRunning = false
            }
        }
    }
}

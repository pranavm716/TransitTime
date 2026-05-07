package io.github.pranavm716.transittime.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.LocusIdCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import io.github.pranavm716.transittime.model.TileRow

class GoModeNotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopId = intent?.getStringExtra("stopId")
        if (stopId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val cache = WearLocalCache(this)
        val snapshot = cache.getSnapshot(stopId)
        val soonestRow = snapshot?.rows?.firstOrNull()

        Log.d("LiveNotif", "GoModeNotificationService.onStartCommand: stopId=$stopId")

        val pendingIntent = PendingIntent.getActivity(
            this,
            888,
            Intent(this, ActionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(ActionActivity.EXTRA_ACTION, "/action/go_mode_toggle")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setColorized(true)
            .setWhen(0)
            .setColor(soonestRow?.iconBgColor ?: Color.GRAY)
            .setLocusId(LocusIdCompat("go_mode"))

        val statusBuilder = Status.Builder()

        if (snapshot != null && soonestRow != null) {
            val soonestTime = soonestRow.displayTimes.firstOrNull() ?: "—"
            val soonestColor = soonestRow.delayColors.firstOrNull() ?: 0xFFAAAAAA.toInt()

            val contentText = SpannableString(soonestTime).apply {
                setSpan(ForegroundColorSpan(soonestColor), 0, length, 0)
            }

            builder.setContentTitle(snapshot.stopName)
            builder.setContentText(contentText)

            statusBuilder.addTemplate("#time# • #headsign#")
                .addPart("headsign", Status.TextPart(soonestRow.headsign))
                .addPart("time", Status.TextPart(soonestTime))

            val routeIcon = drawRouteIcon(soonestRow)
            builder.setSmallIcon(
                androidx.core.graphics.drawable.IconCompat.createFromIcon(
                    this,
                    routeIcon
                )
            )

            val ongoingActivity = OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
                .setStaticIcon(routeIcon)
                .setTouchIntent(pendingIntent)
                .setStatus(statusBuilder.build())
                .setLocusId(LocusIdCompat("go_mode"))
                .build()
            ongoingActivity.apply(this)
        } else {
            builder.setSmallIcon(R.drawable.ic_ongoing_dot)
            builder.setContentTitle("TransitTime")
            builder.setContentText("Loading...")
            statusBuilder.addTemplate("Loading...")

            val ongoingActivity = OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_ongoing_dot)
                .setTouchIntent(pendingIntent)
                .setStatus(statusBuilder.build())
                .setLocusId(LocusIdCompat("go_mode"))
                .build()
            ongoingActivity.apply(this)
        }

        startForeground(NOTIFICATION_ID, builder.build())
        return START_STICKY
    }

    private fun drawRouteIcon(row: TileRow): Icon {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw a full-bleed circle background.
        paint.color = row.iconBgColor
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        val iconText = row.iconText ?: ""
        val baseTextSize = size * 0.5f
        paint.color = if (row.iconTextColor != 0) row.iconTextColor else Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = baseTextSize

        // Scale text size down if it doesn't fit within 80% of the circle's diameter
        val measuredWidth = paint.measureText(iconText)
        val maxWidth = size * 0.8f
        if (measuredWidth > maxWidth) {
            paint.textSize = baseTextSize * (maxWidth / measuredWidth)
        }

        val x = size / 2f
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(iconText, x, y, paint)

        return Icon.createWithBitmap(bitmap)
    }

    override fun onDestroy() {
        Log.d("LiveNotif", "GoModeNotificationService.onDestroy")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Go Mode",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "go_mode_channel_v5"

        @Volatile
        private var lastRunningStopId: String? = null

        fun update(context: Context) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true

            val cache = WearLocalCache(context)
            val localOverride = cache.getLocalGoModeOverride()

            var activeStopId: String? = null

            when (localOverride) {
                true -> {
                    // Force the current stop being viewed on the watch
                    activeStopId = cache.getCurrentStopId()
                    Log.d(
                        "LiveNotif",
                        "update: following local override (active) for stopId=$activeStopId"
                    )
                }

                false -> {
                    // Force deactivation
                    activeStopId = null
                    Log.d("LiveNotif", "update: following local override (inactive)")
                }

                null -> {
                    // Search for the explicit target stop first
                    val stopIds = cache.getStopIds()
                    for (stopId in stopIds) {
                        val snapshot = cache.getSnapshot(stopId)
                        if (snapshot?.goModeTarget == true && snapshot.rows.isNotEmpty()) {
                            activeStopId = stopId
                            Log.d(
                                "LiveNotif",
                                "update: found explicit goModeTarget snapshot: $activeStopId"
                            )
                            break
                        }
                    }

                    // Fallback to searching snapshots with goModeActive (if no target found)
                    if (activeStopId == null) {
                        val currentStopId = cache.getCurrentStopId()
                        val currentSnapshot = currentStopId?.let { cache.getSnapshot(it) }
                        if (currentSnapshot?.goModeActive == true && currentSnapshot.rows.isNotEmpty()) {
                            activeStopId = currentStopId
                            Log.d(
                                "LiveNotif",
                                "update: current stop confirmed active by snapshot: $activeStopId"
                            )
                        } else {
                            for (stopId in stopIds) {
                                if (stopId == currentStopId) continue
                                val snapshot = cache.getSnapshot(stopId)
                                if (snapshot?.goModeActive == true && snapshot.rows.isNotEmpty()) {
                                    activeStopId = stopId
                                    break
                                }
                            }
                            Log.d(
                                "LiveNotif",
                                "update: searched other snapshots, found stopId=$activeStopId"
                            )
                        }
                    }
                }
            }

            val shouldBeRunning = activeStopId != null
            if (!shouldBeRunning) {
                if (lastRunningStopId != null) {
                    Log.d(
                        "LiveNotif",
                        "update: stopping service (previously running for $lastRunningStopId)"
                    )
                    context.stopService(Intent(context, GoModeNotificationService::class.java))
                    lastRunningStopId = null
                }
                return
            }

            // If we should be running
            if (hasPermission) {
                try {
                    val intent = Intent(context, GoModeNotificationService::class.java)
                    intent.putExtra("stopId", activeStopId)
                    context.startForegroundService(intent)
                    lastRunningStopId = activeStopId
                } catch (e: Exception) {
                    Log.e("LiveNotif", "startForegroundService failed", e)
                }
            }
        }
    }
}

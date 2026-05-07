package io.github.pranavm716.transittime

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.service.GoModeNotificationService
import io.github.pranavm716.transittime.wear.TileSnapshotPusher
import io.github.pranavm716.transittime.wear.buildSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GoModeManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Scenario (5): go mode toggled on phone — push updated snapshots for all configs
    var goModeExpiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) {
            prefs.edit { putLong(KEY_EXPIRES_AT, value) }
            GoModeNotificationService.update(appContext)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = TransitDatabase.getInstance(appContext)
                    val configs = db.widgetConfigDao().getAllConfigs()
                    val departureDao = db.departureDao()
                    val pusher = TileSnapshotPusher(appContext)
                    val isGlobalActive = value > System.currentTimeMillis()
                    val activeWidgetId = goModeWidgetId
                    for (config in configs) {
                        val departures = departureDao.getDeparturesForStop(config.stopId)
                        val isTarget = isGlobalActive && config.widgetId == activeWidgetId
                        val snapshot = buildSnapshot(
                            config = config,
                            departures = departures,
                            goModeActive = isGlobalActive,
                            goModeExpiresAt = value,
                            goModeTarget = isTarget
                        )
                        Log.d(TAG, "GoModeManager: pushing snapshot for stopId=${config.stopId}, goModeActive=$isGlobalActive, isTarget=$isTarget")
                        pusher.pushSnapshot(snapshot)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

    val isGoModeActive: Boolean
        get() = goModeExpiresAt > System.currentTimeMillis()

    var goModeWidgetId: Int
        get() = prefs.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) {
            Log.d("LiveNotif", "goModeWidgetId set: $value")
            prefs.edit { putInt(KEY_WIDGET_ID, value) }
            GoModeNotificationService.update(appContext)
        }

    companion object {
        const val GO_MODE_DURATION_MS: Long = 20 * 60 * 1000L
        const val GO_MODE_INTERVAL_MS: Long = 30 * 1000L
        private const val TAG = "TransitWear"
        private const val PREFS_NAME = "transit_go_mode_prefs"
        private const val KEY_EXPIRES_AT = "go_mode_expires_at"
        private const val KEY_WIDGET_ID = "go_mode_widget_id"
    }
}

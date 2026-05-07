package io.github.pranavm716.transittime

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit

class GoModeManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var goModeExpiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) {
            prefs.edit { putLong(KEY_EXPIRES_AT, value) }
            // No longer updating notification/watch immediately here.
            // FetchWorker will handle this once fresh data is ready.
        }

    val isGoModeActive: Boolean
        get() = goModeExpiresAt > System.currentTimeMillis()

    var goModeWidgetId: Int
        get() = prefs.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) {
            Log.d("LiveNotif", "goModeWidgetId set: $value")
            prefs.edit { putInt(KEY_WIDGET_ID, value) }
            // No longer updating notification immediately here.
        }

    companion object {
        const val GO_MODE_DURATION_MS: Long = 20 * 60 * 1000L
        const val GO_MODE_INTERVAL_MS: Long = 30 * 1000L
        private const val PREFS_NAME = "transit_go_mode_prefs"
        private const val KEY_EXPIRES_AT = "go_mode_expires_at"
        private const val KEY_WIDGET_ID = "go_mode_widget_id"
    }
}

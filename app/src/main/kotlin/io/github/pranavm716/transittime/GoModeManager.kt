package io.github.pranavm716.transittime

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.gomode.ActiveStrategy
import io.github.pranavm716.transittime.gomode.GoModeState
import io.github.pranavm716.transittime.gomode.GoModeStrategy
import io.github.pranavm716.transittime.gomode.InactiveStrategy
import io.github.pranavm716.transittime.service.GoModeNotificationService
import io.github.pranavm716.transittime.widget.TransitWidget
import io.github.pranavm716.transittime.worker.FetchWorker
import java.util.concurrent.TimeUnit

class GoModeManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var goModeExpiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) {
            prefs.edit { putLong(KEY_EXPIRES_AT, value) }
        }

    val isGoModeActive: Boolean
        get() = goModeExpiresAt > System.currentTimeMillis()

    var goModeWidgetId: Int
        get() = prefs.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) {
            prefs.edit { putInt(KEY_WIDGET_ID, value) }
        }

    fun getState(): GoModeState {
        return if (isGoModeActive) {
            GoModeState.Active(goModeWidgetId, goModeExpiresAt)
        } else {
            GoModeState.Inactive
        }
    }

    fun getStrategy(): GoModeStrategy {
        return if (isGoModeActive) ActiveStrategy() else InactiveStrategy()
    }

    fun activate(widgetId: Int) {
        Log.d("GoModeManager", "Activating Go Mode for widgetId=$widgetId")
        goModeWidgetId = widgetId
        goModeExpiresAt = System.currentTimeMillis() + GO_MODE_DURATION_MS
        
        syncAll()
        
        // Schedule worker and expiry
        val workManager = WorkManager.getInstance(appContext)
        workManager.enqueueUniqueWork(
            TransitWidget.GO_MODE_FETCH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FetchWorker>()
                .setInitialDelay(GO_MODE_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .build(),
        )
        workManager.enqueueUniqueWork(
            TransitWidget.GO_MODE_EXPIRY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FetchWorker>()
                .setInitialDelay(GO_MODE_DURATION_MS, TimeUnit.MILLISECONDS)
                .build(),
        )
        
        TransitWidget.triggerFetch(appContext)
    }

    fun deactivate() {
        Log.d("GoModeManager", "Deactivating Go Mode")
        goModeWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        goModeExpiresAt = 0
        
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelUniqueWork(TransitWidget.GO_MODE_FETCH_WORK_NAME)
        workManager.cancelUniqueWork(TransitWidget.GO_MODE_EXPIRY_WORK_NAME)
        
        syncAll()
    }

    fun toggle(widgetId: Int) {
        if (isGoModeActive) {
            deactivate()
        } else {
            activate(widgetId)
        }
    }

    private fun syncAll() {
        Log.d("GoModeManager", "syncAll: isGoModeActive=$isGoModeActive")
        
        // 1. Update phone notification
        GoModeNotificationService.update(appContext)
        
        // 2. Update all widgets
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(ComponentName(appContext, TransitWidget::class.java))
        for (id in ids) {
            TransitWidget.updateWidgetAsync(appContext, manager, id)
        }
        
        // 3. Update watch via a snapshot push (FetchWorker handles this by checking isGoModeActive)
        // triggerFetch will push fresh data to the watch.
        TransitWidget.triggerFetch(appContext)
    }

    companion object {
        const val GO_MODE_DURATION_MS: Long = 20 * 60 * 1000L
        const val GO_MODE_INTERVAL_MS: Long = 30 * 1000L
        private const val PREFS_NAME = "transit_go_mode_prefs"
        private const val KEY_EXPIRES_AT = "go_mode_expires_at"
        private const val KEY_WIDGET_ID = "go_mode_widget_id"
    }
}

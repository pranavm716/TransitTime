package io.github.pranavm716.transittime

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import io.github.pranavm716.transittime.data.db.TransitDatabase
import androidx.work.workDataOf
import io.github.pranavm716.transittime.TransitApplication
import io.github.pranavm716.transittime.gomode.ActiveStrategy
import io.github.pranavm716.transittime.gomode.GoModeState
import io.github.pranavm716.transittime.gomode.GoModeStrategy
import io.github.pranavm716.transittime.gomode.InactiveStrategy
import io.github.pranavm716.transittime.service.GoModeNotificationService
import io.github.pranavm716.transittime.wear.TileSnapshotPusher
import io.github.pranavm716.transittime.wear.buildSnapshot
import io.github.pranavm716.transittime.widget.TransitWidget
import io.github.pranavm716.transittime.worker.FetchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    fun getStrategyForWidget(widgetId: Int): GoModeStrategy {
        return if (isGoModeActive && goModeWidgetId == widgetId) ActiveStrategy() else InactiveStrategy()
    }

    fun activate(widgetId: Int) {
        Log.d("GoModeManager", "Activating Go Mode for widgetId=$widgetId")
        goModeWidgetId = widgetId
        goModeExpiresAt = System.currentTimeMillis() + GO_MODE_DURATION_MS

        val workManager = WorkManager.getInstance(appContext)
        workManager.enqueueUniqueWork(
            TransitWidget.GO_MODE_FETCH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FetchWorker>()
                .setInputData(workDataOf(FetchWorker.KEY_GO_MODE_ONLY to true))
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

        val request = OneTimeWorkRequestBuilder<FetchWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(FetchWorker.KEY_GO_MODE_ONLY to true))
            .build()
        workManager.enqueueUniqueWork(
            TransitApplication.FETCH_WORK_NAME_MANUAL,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun deactivate() {
        Log.d("GoModeManager", "Deactivating Go Mode")
        goModeWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        goModeExpiresAt = 0

        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelUniqueWork(TransitWidget.GO_MODE_FETCH_WORK_NAME)
        workManager.cancelUniqueWork(TransitWidget.GO_MODE_EXPIRY_WORK_NAME)

        TransitWidget.triggerFetch(appContext)

        GoModeNotificationService.update(appContext)

        // Flip widget styles immediately without re-rendering or network calls.
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(ComponentName(appContext, TransitWidget::class.java))

        // Push cached snapshots to the watch so it clears go mode display immediately.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = TransitDatabase.getInstance(appContext)
                val allConfigs = db.widgetConfigDao().getAllConfigs()
                
                // Update phone widgets immediately with correct error state colors
                for (id in ids) {
                    val hasError = allConfigs.find { it.widgetId == id }?.lastErrorLabel != null
                    TransitWidget.updateGoModeStyle(appContext, manager, id, false, hasError)
                }

                val configs = allConfigs.groupBy { it.stopId }.map { (_, list) -> list.first() }
                val pusher = TileSnapshotPusher(appContext)
                for (config in configs) {
                    val deps = db.departureDao().getDeparturesForStop(config.stopId)
                    pusher.pushSnapshot(
                        buildSnapshot(
                            config = config,
                            departures = deps,
                            goModeActive = false,
                            goModeExpiresAt = 0L,
                            isRefreshing = false,
                            goModeTarget = false
                        ),
                        isFetchResult = true
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun toggle(widgetId: Int) {
        if (!isGoModeActive) {
            activate(widgetId)
            focusWatchOnWidget(widgetId)
        } else if (goModeWidgetId == widgetId) {
            deactivate()
        } else {
            val prevWidgetId = goModeWidgetId
            val manager = AppWidgetManager.getInstance(appContext)
            TransitWidget.updateGoModeStyle(appContext, manager, prevWidgetId, active = false)
            activate(widgetId)
            focusWatchOnWidget(widgetId)
            // Clear the old widget's watch snapshot so its tile loses the green dot and the
            // watch pill stops showing its departure.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = TransitDatabase.getInstance(appContext)
                    val prevConfig = db.widgetConfigDao().getConfig(prevWidgetId) ?: return@launch
                    val deps = db.departureDao().getDeparturesForStop(prevConfig.stopId)
                    TileSnapshotPusher(appContext).pushSnapshot(
                        buildSnapshot(
                            config = prevConfig,
                            departures = deps,
                            goModeActive = false,
                            goModeExpiresAt = 0L,
                            isRefreshing = false,
                            goModeTarget = false
                        ),
                        isFetchResult = true
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun focusWatchOnWidget(widgetId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = TransitDatabase.getInstance(appContext)
                    .widgetConfigDao().getConfig(widgetId) ?: return@launch
                TileSnapshotPusher(appContext).pushFocusStop(config.stopId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val GO_MODE_DURATION_MS: Long = 20 * 60 * 1000L
        const val GO_MODE_INTERVAL_MS: Long = 30 * 1000L
        private const val PREFS_NAME = "transit_go_mode_prefs"
        private const val KEY_EXPIRES_AT = "go_mode_expires_at"
        private const val KEY_WIDGET_ID = "go_mode_widget_id"
    }
}

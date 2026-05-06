package io.github.pranavm716.transittime.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.pranavm716.transittime.GoModeManager
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.transit.AgencyRegistry
import io.github.pranavm716.transittime.transit.TransitError
import io.github.pranavm716.transittime.wear.TileSnapshotPusher
import io.github.pranavm716.transittime.wear.buildSnapshot
import io.github.pranavm716.transittime.widget.TransitWidget
import java.util.concurrent.TimeUnit

class FetchWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TransitWear"
    }

    override suspend fun doWork(): Result {
        val db = TransitDatabase.getInstance(context)
        val departureDao = db.departureDao()
        val configDao = db.widgetConfigDao()

        val manager = AppWidgetManager.getInstance(context)
        val activeIds = manager.getAppWidgetIds(
            ComponentName(context, TransitWidget::class.java)
        ).toSet()

        val goModeManager = GoModeManager(context)
        val pusher = TileSnapshotPusher(context)

        if (goModeManager.isGoModeActive) {
            for (id in activeIds) {
                TransitWidget.animateGoModeDot(context, manager, id)
            }
        } else {
            for (id in activeIds) {
                TransitWidget.animateRefreshIcon(context, manager, id)
            }
        }

        val allConfigs = configDao.getAllConfigs()
        val staleConfigs = allConfigs.filter { it.widgetId !in activeIds }
        val clearedStopIds = mutableSetOf<String>()
        if (staleConfigs.isNotEmpty()) {
            val removedStopIds = staleConfigs.map { it.stopId }.toSet()
            val staleWidgetIds = staleConfigs.map { it.widgetId }.toSet()
            staleConfigs.forEach { configDao.deleteConfig(it.widgetId) }
            val survivingConfigs = allConfigs.filter { it.widgetId !in staleWidgetIds }
            removedStopIds.forEach { stopId ->
                val remaining = survivingConfigs.filter { it.stopId == stopId }
                if (remaining.isEmpty()) {
                    departureDao.deleteDeparturesForStop(stopId)
                    clearedStopIds.add(stopId)
                }
            }
        }

        val configs = if (staleConfigs.isNotEmpty()) configDao.getAllConfigs() else allConfigs
        val fetchedAt = System.currentTimeMillis()

        for (stopId in clearedStopIds) {
            try {
                Log.d(TAG, "FetchWorker: deleting snapshot for cleared stopId=$stopId")
                pusher.deleteSnapshot(stopId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (configs.isEmpty()) {
            try {
                Log.d(TAG, "FetchWorker: no configs, pushing empty stop index")
                pusher.pushStopIndex(emptyList())
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return Result.success()
        }

        // Signal fetch in progress by pushing loading snapshots with isRefreshing=true.
        for (config in configs) {
            try {
                val deps = departureDao.getDeparturesForStop(config.stopId)
                val loading = buildSnapshot(config, deps, goModeManager.isGoModeActive, goModeManager.goModeExpiresAt, isRefreshing = true)
                Log.d(TAG, "FetchWorker: pushing loading snapshot for stopId=${config.stopId}")
                pusher.pushSnapshot(loading)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        configs.groupBy { it.agency }.forEach { (agency, agencyConfigs) ->
            val handler = AgencyRegistry.get(agency)
            try {
                handler.loadStaticData(context)
                val stopIds = agencyConfigs.map { it.stopId }.toSet()
                val result = handler.fetchDepartures(stopIds, fetchedAt)
                val departuresByStop = result.departures.groupBy { it.stopId }

                for (stopId in stopIds) {
                    if (stopId in result.stopErrors) continue
                    val stopDepartures = departuresByStop[stopId] ?: emptyList()
                    val existing = departureDao.getDeparturesForStop(stopId)

                    if (stopDepartures.isEmpty()) {
                        if (existing.isNotEmpty()) {
                            departureDao.deleteDeparturesForStop(stopId)
                        }
                    } else {
                        val existingSignature = existing.map { it.id to it.departureTimestamp }.toSet()
                        val newSignature = stopDepartures.map { it.id to it.departureTimestamp }.toSet()
                        if (existingSignature != newSignature) {
                            departureDao.upsertDepartures(stopDepartures)
                            departureDao.deleteStaleRowsForStop(stopId, stopDepartures.map { it.id })
                        }
                    }
                }

                for (config in agencyConfigs) {
                    val stopException = result.stopErrors[config.stopId]
                    if (stopException != null) {
                        val error = TransitError.fromException(stopException)
                        val current = configDao.getConfig(config.widgetId) ?: continue
                        if (current.lastFetchedAt > config.lastFetchedAt) continue
                        configDao.upsertConfig(current.copy(lastErrorLabel = error.label))
                    } else {
                        configDao.upsertConfig(config.copy(lastFetchedAt = fetchedAt, lastErrorLabel = null))
                    }
                    TransitWidget.updateWidget(context, manager, config.widgetId)
                    // Scenario (4): widget refreshed — push updated snapshot
                    try {
                        val latestConfig = configDao.getConfig(config.widgetId)
                        if (latestConfig != null) {
                            val deps = departureDao.getDeparturesForStop(latestConfig.stopId)
                            val snapshot = buildSnapshot(latestConfig, deps, goModeManager.isGoModeActive, goModeManager.goModeExpiresAt)
                            Log.d(TAG, "FetchWorker: pushing snapshot for stopId=${latestConfig.stopId}")
                            pusher.pushSnapshot(snapshot, isFetchResult = true)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val error = TransitError.fromException(e)
                for (config in agencyConfigs) {
                    val current = configDao.getConfig(config.widgetId) ?: continue
                    if (current.lastFetchedAt > config.lastFetchedAt) continue
                    configDao.upsertConfig(current.copy(lastErrorLabel = error.label))
                    TransitWidget.updateWidget(context, manager, config.widgetId)
                    // Scenario (4): agency error path — push snapshot with error label
                    try {
                        val latestConfig = configDao.getConfig(config.widgetId)
                        if (latestConfig != null) {
                            val deps = departureDao.getDeparturesForStop(latestConfig.stopId)
                            val snapshot = buildSnapshot(latestConfig, deps, goModeManager.isGoModeActive, goModeManager.goModeExpiresAt)
                            Log.d(TAG, "FetchWorker: pushing snapshot for stopId=${latestConfig.stopId} (agency error)")
                            pusher.pushSnapshot(snapshot, isFetchResult = true)
                        }
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                }
            }
        }

        try {
            val allStopIds = configs.map { it.stopId }.distinct()
            Log.d(TAG, "FetchWorker: pushing stop index, stopIds=$allStopIds")
            pusher.pushStopIndex(allStopIds)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (goModeManager.isGoModeActive) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                TransitWidget.GO_MODE_FETCH_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FetchWorker>()
                    .setInitialDelay(GoModeManager.GO_MODE_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .build()
            )
        } else if (goModeManager.goModeExpiresAt > 0L) {
            goModeManager.goModeExpiresAt = 0L
        }

        return Result.success()
    }
}

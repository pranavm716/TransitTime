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
import io.github.pranavm716.transittime.data.model.WidgetConfig
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
        val now = System.currentTimeMillis()
        val activeGoModeWidgetId = goModeManager.goModeWidgetId

        val staleConfigs = allConfigs.filter { 
            it.widgetId !in activeIds && 
            it.lastFetchedAt > 0 && 
            (now - it.lastFetchedAt > 60000)
        }
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

        val configs = configDao.getAllConfigs()
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

        // Helper to get only one config per stop ID for watch snapshots,
        // preferring the one that matches goModeWidgetId.
        fun getDeduplicatedConfigs(input: List<WidgetConfig>): List<WidgetConfig> {
            return input.groupBy { it.stopId }.map { (_, stopConfigs) ->
                stopConfigs.find { it.widgetId == activeGoModeWidgetId } ?: stopConfigs.first()
            }
        }

        // Signal fetch in progress by pushing loading snapshots with isRefreshing=true.
        for (config in getDeduplicatedConfigs(configs)) {
            try {
                val deps = departureDao.getDeparturesForStop(config.stopId)
                val isGlobalActive = goModeManager.isGoModeActive
                val isTarget = isGlobalActive && config.widgetId == activeGoModeWidgetId
                val loading = buildSnapshot(
                    config = config,
                    departures = deps,
                    goModeActive = isGlobalActive,
                    goModeExpiresAt = goModeManager.goModeExpiresAt,
                    isRefreshing = true,
                    goModeTarget = isTarget
                )
                Log.d(TAG, "FetchWorker: pushing loading snapshot for stopId=${config.stopId}, active=$isGlobalActive, target=$isTarget")
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

                val updatedConfigs = configDao.getAllConfigs()
                val deduplicatedForSnapshots = getDeduplicatedConfigs(updatedConfigs)

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

                    // Scenario (4): widget refreshed — push updated snapshot IF it's the chosen one for this stop
                    if (deduplicatedForSnapshots.any { it.widgetId == config.widgetId }) {
                        try {
                            val latestConfig = configDao.getConfig(config.widgetId)
                            if (latestConfig != null) {
                                val deps = departureDao.getDeparturesForStop(latestConfig.stopId)
                                val isGlobalActive = goModeManager.isGoModeActive
                                val isTarget = isGlobalActive && latestConfig.widgetId == activeGoModeWidgetId
                                val snapshot = buildSnapshot(
                                    config = latestConfig,
                                    departures = deps,
                                    goModeActive = isGlobalActive,
                                    goModeExpiresAt = goModeManager.goModeExpiresAt,
                                    goModeTarget = isTarget
                                )
                                Log.d(TAG, "FetchWorker: pushing snapshot for stopId=${latestConfig.stopId}, active=$isGlobalActive, target=$isTarget")
                                pusher.pushSnapshot(snapshot, isFetchResult = true)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val error = TransitError.fromException(e)
                val updatedConfigs = configDao.getAllConfigs()
                val deduplicatedForSnapshots = getDeduplicatedConfigs(updatedConfigs)

                for (config in agencyConfigs) {
                    val current = configDao.getConfig(config.widgetId) ?: continue
                    if (current.lastFetchedAt > config.lastFetchedAt) continue
                    configDao.upsertConfig(current.copy(lastErrorLabel = error.label))
                    TransitWidget.updateWidget(context, manager, config.widgetId)

                    if (deduplicatedForSnapshots.any { it.widgetId == config.widgetId }) {
                        try {
                            val latestConfig = configDao.getConfig(config.widgetId)
                            if (latestConfig != null) {
                                val deps = departureDao.getDeparturesForStop(latestConfig.stopId)
                                val isGlobalActive = goModeManager.isGoModeActive
                                val isTarget = isGlobalActive && latestConfig.widgetId == activeGoModeWidgetId
                                val snapshot = buildSnapshot(
                                    config = latestConfig,
                                    departures = deps,
                                    goModeActive = isGlobalActive,
                                    goModeExpiresAt = goModeManager.goModeExpiresAt,
                                    goModeTarget = isTarget
                                )
                                Log.d(TAG, "FetchWorker: pushing snapshot for stopId=${latestConfig.stopId} (agency error), active=$isGlobalActive, target=$isTarget")
                                pusher.pushSnapshot(snapshot, isFetchResult = true)
                            }
                        } catch (e2: Exception) {
                            e2.printStackTrace()
                        }
                    }
                }
            }
        }

        try {
            val allStopIds = configDao.getAllConfigs().map { it.stopId }.distinct()
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

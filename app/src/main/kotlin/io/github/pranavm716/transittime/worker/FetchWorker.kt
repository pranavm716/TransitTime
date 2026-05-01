package io.github.pranavm716.transittime.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.pranavm716.transittime.GoModeManager
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.transit.AgencyRegistry
import io.github.pranavm716.transittime.widget.TransitWidget
import java.util.concurrent.TimeUnit

class FetchWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = TransitDatabase.getInstance(context)
        val departureDao = db.departureDao()
        val configDao = db.widgetConfigDao()

        val manager = AppWidgetManager.getInstance(context)
        val activeIds = manager.getAppWidgetIds(
            ComponentName(context, TransitWidget::class.java)
        ).toSet()

        val goModeManager = GoModeManager(context)
        if (goModeManager.isGoModeActive) {
            for (id in activeIds) {
                TransitWidget.animateGoModeDot(context, manager, id)
            }
        }

        val allConfigs = configDao.getAllConfigs()
        val staleConfigs = allConfigs.filter { it.widgetId !in activeIds }
        if (staleConfigs.isNotEmpty()) {
            val removedStopIds = staleConfigs.map { it.stopId }.toSet()
            val staleWidgetIds = staleConfigs.map { it.widgetId }.toSet()
            staleConfigs.forEach { configDao.deleteConfig(it.widgetId) }
            val survivingConfigs = allConfigs.filter { it.widgetId !in staleWidgetIds }
            removedStopIds.forEach { stopId ->
                val remaining = survivingConfigs.filter { it.stopId == stopId }
                if (remaining.isEmpty()) departureDao.deleteDeparturesForStop(stopId)
            }
        }

        val configs = if (staleConfigs.isNotEmpty()) configDao.getAllConfigs() else allConfigs
        if (configs.isEmpty()) return Result.success()

        val fetchedAt = System.currentTimeMillis()
        val agencyErrors = mutableMapOf<io.github.pranavm716.transittime.data.model.Agency, io.github.pranavm716.transittime.transit.TransitError>()
        val changedStops = mutableSetOf<String>()

        configs.groupBy { it.agency }.forEach { (agency, agencyConfigs) ->
            val handler = AgencyRegistry.get(agency)
            try {
                handler.loadStaticData(context)
                val stopIds = agencyConfigs.map { it.stopId }.toSet()
                val departures = handler.fetchArrivals(stopIds, fetchedAt)
                val departuresByStop = departures.groupBy { it.stopId }

                for (stopId in stopIds) {
                    val stopDepartures = departuresByStop[stopId] ?: emptyList()
                    val existing = departureDao.getDeparturesForStop(stopId)
                    
                    if (stopDepartures.isEmpty()) {
                        if (existing.isNotEmpty()) {
                            changedStops.add(stopId)
                            departureDao.deleteDeparturesForStop(stopId)
                        }
                    } else {
                        val existingSignature = existing.map { it.id to it.departureTimestamp }.toSet()
                        val newSignature = stopDepartures.map { it.id to it.departureTimestamp }.toSet()
                        if (existingSignature != newSignature) {
                            changedStops.add(stopId)
                            departureDao.upsertDepartures(stopDepartures)
                            departureDao.deleteStaleRowsForStop(stopId, stopDepartures.map { it.id })
                        }
                    }
                }

                for (config in agencyConfigs) {
                    configDao.upsertConfig(config.copy(
                        lastFetchedAt = fetchedAt,
                        lastErrorLabel = null
                    ))
                    // Update widget immediately after successful agency fetch
                    TransitWidget.updateWidget(context, manager, config.widgetId, now = fetchedAt)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val error = io.github.pranavm716.transittime.transit.TransitError.fromException(e)
                agencyErrors[agency] = error
                for (config in agencyConfigs) {
                    configDao.upsertConfig(config.copy(lastErrorLabel = error.label))
                    // Update widget immediately to show the error
                    TransitWidget.updateWidget(context, manager, config.widgetId, now = fetchedAt)
                }
            }
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
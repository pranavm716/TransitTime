package io.github.pranavm716.transittime.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.transit.AgencyRegistry
import io.github.pranavm716.transittime.widget.TransitWidget

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
        val failedAgencies = mutableSetOf<io.github.pranavm716.transittime.data.model.Agency>()
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
                    if (stopDepartures.isNotEmpty()) {
                        val existing = departureDao.getDeparturesForStop(stopId)
                        val existingSignature =
                            existing.map { it.id to it.departureTimestamp }.toSet()
                        val newSignature =
                            stopDepartures.map { it.id to it.departureTimestamp }.toSet()
                        if (existingSignature != newSignature) {
                            changedStops.add(stopId)
                            departureDao.upsertDepartures(stopDepartures)
                            departureDao.deleteStaleRowsForStop(
                                stopId,
                                stopDepartures.map { it.id })
                        }
                    }
                }

                for (config in agencyConfigs) {
                    configDao.upsertConfig(config.copy(lastFetchedAt = fetchedAt))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                failedAgencies.add(agency)
            }
        }

        val ids = manager.getAppWidgetIds(
            ComponentName(context, TransitWidget::class.java)
        )
        val configByWidgetId = configs.associateBy { it.widgetId }
        for (id in ids) {
            val config = configByWidgetId[id] ?: continue
            val fetchFailed = config.agency in failedAgencies
            TransitWidget.updateWidget(
                context, manager, id,
                fetchFailed = fetchFailed,
                fetchedAt = if (fetchFailed) null else fetchedAt
            )
        }

        return Result.success()
    }
}
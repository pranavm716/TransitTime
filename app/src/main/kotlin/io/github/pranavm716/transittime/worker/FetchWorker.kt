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
        val arrivalDao = db.arrivalDao()
        val configDao = db.widgetConfigDao()

        val configs = configDao.getAllConfigs()
        if (configs.isEmpty()) return Result.success()

        val fetchedAt = System.currentTimeMillis()
        val failedAgencies = mutableSetOf<io.github.pranavm716.transittime.data.model.Agency>()

        configs.groupBy { it.agency }.forEach { (agency, agencyConfigs) ->
            val handler = AgencyRegistry.get(agency)
            try {
                handler.loadStaticData(context)
                val stopIds = agencyConfigs.map { it.stopId }.toSet()
                val arrivals = handler.fetchArrivals(stopIds, fetchedAt)
                arrivals.groupBy { it.stopId }.forEach { (stopId, stopArrivals) ->
                    if (stopArrivals.isNotEmpty()) {
                        arrivalDao.deleteArrivalsForStop(stopId)
                        arrivalDao.upsertArrivals(stopArrivals)
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

        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, TransitWidget::class.java)
        )
        val configByWidgetId = configs.associateBy { it.widgetId }
        for (id in ids) {
            val fetchFailed = configByWidgetId[id]?.agency in failedAgencies
            TransitWidget.updateWidget(context, manager, id, fetchFailed = fetchFailed)
        }

        return Result.success()
    }
}

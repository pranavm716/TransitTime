package io.github.pranavm716.transittime.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pranavm716.transittime.data.api.bart.BartApiClient
import io.github.pranavm716.transittime.data.api.bart.BartParser
import io.github.pranavm716.transittime.data.api.muni.MuniParser
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
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

        val bartConfigs = configs.filter { it.agency == Agency.BART }
        val muniConfigs = configs.filter { it.agency == Agency.MUNI }

        val bartStopIds = bartConfigs.map { it.stopId }.toSet()
        val muniStopIds = muniConfigs.map { it.stopId }.toSet()

        val fetchedAt = System.currentTimeMillis()

        // Fetch BART
        if (bartStopIds.isNotEmpty()) {
            try {
                BartParser.loadStaticGtfs(context)
                val bytes = BartApiClient.api.getTripUpdates().bytes()
                val arrivals = BartParser.parseRtFeed(bytes, fetchedAt)
                    .filter { it.stopId in bartStopIds }

                for (stopId in bartStopIds) {
                    arrivalDao.deleteArrivalsForStop(stopId)
                }
                arrivalDao.upsertArrivals(arrivals)

                for (config in bartConfigs) {
                    configDao.upsertConfig(config.copy(lastFetchedAt = fetchedAt))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fetch MUNI
        if (muniStopIds.isNotEmpty()) {
            MuniParser.loadStaticGtfs(context)

            for (stopId in muniStopIds) {
                try {
                    val arrivals = MuniParser.fetchAndParseStop(stopId, fetchedAt)
                    if (arrivals.isNotEmpty()) {
                        arrivalDao.deleteArrivalsForStop(stopId)
                        arrivalDao.upsertArrivals(arrivals)
                    }
                    // Always update lastFetchedAt — even if no arrivals, the fetch succeeded
                    val config = configDao.getConfigByStopId(stopId)
                    config?.let {
                        configDao.upsertConfig(it.copy(lastFetchedAt = fetchedAt))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Redraw all widgets
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, TransitWidget::class.java)
        )
        for (id in ids) {
            TransitWidget.updateWidget(context, manager, id)
        }

        return Result.success()
    }
}
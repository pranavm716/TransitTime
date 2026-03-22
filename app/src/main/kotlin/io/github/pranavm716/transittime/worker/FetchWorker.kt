package io.github.pranavm716.transittime.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pranavm716.transittime.data.api.bart.BartApiClient
import io.github.pranavm716.transittime.data.api.bart.BartParser
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency

class FetchWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = TransitDatabase.getInstance(context)
        val arrivalDao = db.arrivalDao()
        val configDao = db.widgetConfigDao()

        // 1. Get all configured widget stops — if none exist, nothing to fetch
        val configs = configDao.getAllConfigs()
        if (configs.isEmpty()) return Result.success()

        // 2. Group configured stop IDs by agency
        val bartStopIds = configs
            .filter { it.agency == Agency.BART }
            .map { it.stopId }
            .toSet()

        val fetchedAt = System.currentTimeMillis()

        // 3. Delete arrivals older than 5 minutes
        arrivalDao.deleteStaleArrivals(fetchedAt - 5 * 60 * 1000)

        // 4. Fetch and parse BART
        if (bartStopIds.isNotEmpty()) {
            try {
                BartParser.loadStaticGtfs(context)
                val bytes = BartApiClient.api.getTripUpdates().bytes()
                val arrivals = BartParser.parseRtFeed(bytes, fetchedAt)
                    .filter { it.stopId in bartStopIds }
                arrivalDao.upsertArrivals(arrivals)
            } catch (e: Exception) {
                // Don't fail the whole job if one agency fails
                e.printStackTrace()
            }
        }

        // 5. Notify widgets to redraw
//        val manager = AppWidgetManager.getInstance(context)
//        val widgetIds = manager.getAppWidgetIds(
//            ComponentName(context, TransitWidget::class.java)
//        )
//        manager.notifyAppWidgetViewDataChanged(widgetIds, android.R.id.list)

        return Result.success()
    }
}
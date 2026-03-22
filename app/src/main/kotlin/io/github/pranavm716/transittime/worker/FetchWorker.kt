package io.github.pranavm716.transittime.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pranavm716.transittime.data.api.bart.BartApiClient
import io.github.pranavm716.transittime.data.api.bart.BartParser
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

        // 1. Get all configured widget stops
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
                val filtered = arrivals.filter { it.stopId in bartStopIds }
                arrivalDao.upsertArrivals(filtered)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 5. Redraw all widgets with fresh data
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
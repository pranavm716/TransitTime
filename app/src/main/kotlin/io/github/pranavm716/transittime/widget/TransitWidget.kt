package io.github.pranavm716.transittime.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.data.model.WidgetConfig
import io.github.pranavm716.transittime.transit.AgencyRegistry
import io.github.pranavm716.transittime.util.RouteIconDrawer
import io.github.pranavm716.transittime.worker.FetchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransitWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val db = TransitDatabase.getInstance(context)
        CoroutineScope(Dispatchers.IO).launch {
            for (widgetId in appWidgetIds) {
                db.widgetConfigDao().deleteConfig(widgetId)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        const val ACTION_REFRESH = "io.github.pranavm716.transittime.ACTION_REFRESH"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        private const val HEADER_DP = 44
        private const val ROW_DP = 40
        private const val PADDING_DP = 30

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            fetchFailed: Boolean = false
        ) {
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val maxRows = ((minHeight - HEADER_DP - PADDING_DP) / ROW_DP).coerceAtLeast(1)

            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            val refreshIntent = Intent(context, TransitWidget::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, refreshPendingIntent)

            CoroutineScope(Dispatchers.IO).launch {
                val db = TransitDatabase.getInstance(context)
                val config = db.widgetConfigDao().getConfig(widgetId) ?: run {
                    views.setTextViewText(R.id.tvStopName, "Not configured")
                    appWidgetManager.updateAppWidget(widgetId, views)
                    return@launch
                }

                val now = System.currentTimeMillis()
                val (grouped, overflow) = loadGroupedDepartures(db, config, now, maxRows)

                applyHeader(views, config)
                applyFreshness(views, db, config, fetchFailed)
                applyDepartures(context, views, grouped, config, now)
                applyOverflow(views, overflow)

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun applyHeader(views: RemoteViews, config: WidgetConfig) {
            views.setTextViewText(R.id.tvStopName, config.stopName)
            val logoRes = when (config.agency) {
                Agency.BART -> R.drawable.ic_bart
                Agency.MUNI -> R.drawable.ic_muni
                Agency.CALTRAIN -> R.drawable.ic_caltrain
            }
            views.setImageViewResource(R.id.ivAgencyLogo, logoRes)
        }

        private suspend fun applyFreshness(
            views: RemoteViews,
            db: TransitDatabase,
            config: WidgetConfig,
            fetchFailed: Boolean
        ) {
            if (fetchFailed) {
                views.setTextViewText(R.id.tvFreshnessText, "Failed")
                views.setTextColor(R.id.tvFreshnessText, 0xFFFF6B6B.toInt())
            } else {
                val lastFetchedAt = config.lastFetchedAt.takeIf { it > 0L }
                    ?: db.departureDao().getDeparturesForStop(config.stopId)
                        .maxOfOrNull { it.fetchedAt } ?: 0L
                val freshnessText = if (lastFetchedAt == 0L) "—"
                else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(lastFetchedAt))
                views.setTextViewText(R.id.tvFreshnessText, freshnessText)
                views.setTextColor(R.id.tvFreshnessText, 0xFFAAAAAA.toInt())
            }
        }

        private suspend fun loadGroupedDepartures(
            db: TransitDatabase,
            config: WidgetConfig,
            now: Long,
            maxRows: Int
        ): Pair<List<List<Departure>>, Int> {
            val allGroups = db.departureDao()
                .getDeparturesForStop(config.stopId)
                .filter { departure ->
                    (departure.departureTimestamp ?: departure.arrivalTimestamp ?: Long.MIN_VALUE) > now &&
                            (config.filteredHeadsigns.isEmpty() ||
                                    "${departure.routeName}|${departure.headsign}" in config.filteredHeadsigns)
                }
                .groupBy { "${it.routeName}|${it.headsign}" }
                .entries
                .map { (_, departures) ->
                    departures.sortedBy { it.arrivalTimestamp ?: it.departureTimestamp ?: Long.MAX_VALUE }
                        .take(config.maxArrivals)
                }
                .sortedWith(
                    compareBy(
                        { it.first().arrivalTimestamp ?: it.first().departureTimestamp ?: Long.MAX_VALUE },
                        { it.getOrNull(1)?.let { d -> d.arrivalTimestamp ?: d.departureTimestamp } ?: Long.MAX_VALUE },
                        { it.getOrNull(2)?.let { d -> d.arrivalTimestamp ?: d.departureTimestamp } ?: Long.MAX_VALUE },
                        { it.first().routeName }
                    ))
            val overflow = (allGroups.size - maxRows).coerceAtLeast(0)
            return allGroups.take(maxRows) to overflow
        }

        private fun applyDepartures(
            context: Context,
            views: RemoteViews,
            grouped: List<List<Departure>>,
            config: WidgetConfig,
            now: Long
        ) {
            views.removeAllViews(R.id.llArrivals)

            if (grouped.isEmpty()) {
                views.addView(R.id.llArrivals, RemoteViews(context.packageName, R.layout.widget_empty))
                return
            }

            val allTimes = grouped.map { departures ->
                departures.map { departure ->
                    departure.getDisplayTime(now, config.displayMode, config.hybridThresholdMinutes)
                }
            }
            val globalMaxTimeLen = allTimes.maxOfOrNull { times ->
                (0 until config.maxArrivals).maxOfOrNull { i ->
                    (times.getOrNull(i) ?: "—").length
                } ?: 0
            } ?: 0
            val timeFontSizeSp = when {
                globalMaxTimeLen >= 7 -> 15f  // e.g. "12:30PM", "Leaving"
                else -> 16f
            }

            for ((departures, times) in grouped.zip(allTimes)) {
                views.addView(
                    R.id.llArrivals,
                    buildDepartureRow(context, departures, times, timeFontSizeSp, config.maxArrivals)
                )
            }
        }

        private fun buildDepartureRow(
            context: Context,
            departures: List<Departure>,
            times: List<String>,
            timeFontSizeSp: Float,
            maxArrivals: Int
        ): RemoteViews {
            val first = departures.first()
            val handler = AgencyRegistry.get(first.agency)
            val rowViews = RemoteViews(context.packageName, R.layout.widget_arrival_row)

            val iconSizePx = (36 * context.resources.displayMetrics.density).toInt()
            val bitmap = RouteIconDrawer.draw(
                style = handler.getRouteStyle(first.routeName),
                text = handler.getIconText(first.routeName),
                sizePx = iconSizePx
            )
            rowViews.setImageViewBitmap(R.id.ivRouteIcon, bitmap)
            rowViews.setTextViewText(R.id.tvHeadsign, first.headsign)

            val timeCells = listOf(R.id.tvTime1, R.id.tvTime2, R.id.tvTime3)

            for (i in timeCells.indices) {
                rowViews.setViewVisibility(timeCells[i], if (i < maxArrivals) View.VISIBLE else View.GONE)
            }
            for (cell in timeCells) {
                rowViews.setTextViewTextSize(cell, TypedValue.COMPLEX_UNIT_SP, timeFontSizeSp)
            }
            for (i in 0 until maxArrivals) {
                val text = times.getOrNull(i) ?: "—"
                val isScheduled = departures.getOrNull(i)?.isScheduled == true
                val color = when (text) {
                    "Leaving" -> 0xFFdc3545.toInt()
                    "Arriving" -> 0xFF28a745.toInt()
                    "", "—" -> 0xFFBDC1C7.toInt()
                    else -> if (isScheduled) 0xFF9E8400.toInt() else 0xFFFFD700.toInt()
                }
                rowViews.setTextViewText(timeCells[i], text)
                rowViews.setTextColor(timeCells[i], color)
            }
            return rowViews
        }

        private fun applyOverflow(views: RemoteViews, overflow: Int) {
            views.setTextViewText(
                R.id.tvOverflowStatic,
                if (overflow > 0) "+$overflow more route${if (overflow > 1) "s" else ""}" else ""
            )
        }

        fun animateRefreshIcon(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                val steps = 12
                val stepAngle = 360f / steps
                repeat(steps) { i ->
                    val angle = stepAngle * (i + 1)
                    val views = RemoteViews(context.packageName, R.layout.widget_layout)
                    views.setFloat(R.id.ivRefreshIcon, "setRotation", angle)
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, views)
                    delay(40)
                }
            }
        }

        fun triggerFetch(context: Context) {
            val request = OneTimeWorkRequestBuilder<FetchWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "transit_fetch_manual",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val widgetId = intent.getIntExtra(
                EXTRA_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                animateRefreshIcon(context, appWidgetManager, widgetId)
                triggerFetch(context)
                updateWidget(context, appWidgetManager, widgetId)
            }
        }
    }
}

package io.github.pranavm716.transittime.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.transit.AgencyRegistry
import io.github.pranavm716.transittime.util.RouteIconDrawer
import io.github.pranavm716.transittime.worker.FetchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            widgetId: Int
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
                val config = db.widgetConfigDao().getConfig(widgetId)

                if (config == null) {
                    views.setTextViewText(R.id.tvStopName, "Not configured")
                    appWidgetManager.updateAppWidget(widgetId, views)
                    return@launch
                }

                views.setTextViewText(R.id.tvStopName, config.stopName)

                val logoRes = when (config.agency) {
                    Agency.BART -> R.drawable.ic_bart
                    Agency.MUNI -> R.drawable.ic_muni
                }
                views.setImageViewResource(R.id.ivAgencyLogo, logoRes)

                val now = System.currentTimeMillis()

                val allGroups = db.arrivalDao()
                    .getArrivalsForStop(config.stopId)
                    .filter { arrival ->
                        arrival.departureTimestamp > now &&
                                (config.filteredHeadsigns.isEmpty() ||
                                        "${arrival.routeName}|${arrival.headsign}" in config.filteredHeadsigns)
                    }
                    .groupBy { "${it.routeName}|${it.headsign}" }
                    .entries
                    .map { (_, arrivals) ->
                        arrivals.sortedBy { it.arrivalTimestamp }.take(config.maxArrivals)
                    }
                    .sortedWith(
                        compareBy(
                            { it.first().arrivalTimestamp },
                            { it.getOrNull(1)?.arrivalTimestamp ?: Long.MAX_VALUE },
                            { it.getOrNull(2)?.arrivalTimestamp ?: Long.MAX_VALUE },
                            { it.first().routeName }
                        ))

                val totalGroups = allGroups.size
                val grouped = allGroups.take(maxRows)
                val overflow = totalGroups - maxRows

                val lastFetchedAt = config.lastFetchedAt.takeIf { it > 0L }
                    ?: db.arrivalDao().getArrivalsForStop(config.stopId)
                        .maxOfOrNull { it.fetchedAt } ?: 0L
                val freshnessText = if (lastFetchedAt == 0L) {
                    "—"
                } else {
                    val formatter = SimpleDateFormat("h:mm a ↻", Locale.getDefault())
                    formatter.format(Date(lastFetchedAt))
                }
                views.setTextViewText(R.id.tvFreshnessText, freshnessText)

                views.removeAllViews(R.id.llArrivals)

                if (grouped.isEmpty()) {
                    val emptyViews = RemoteViews(context.packageName, R.layout.widget_empty)
                    views.addView(R.id.llArrivals, emptyViews)
                } else {
                    for (arrivals in grouped) {
                        val first = arrivals.first()
                        val rowViews = RemoteViews(context.packageName, R.layout.widget_arrival_row)

                        val iconSizePx = (36 * context.resources.displayMetrics.density).toInt()
                        val handler = AgencyRegistry.get(first.agency)
                        val bitmap = RouteIconDrawer.draw(
                            style = handler.getRouteStyle(first.routeName),
                            text = handler.getIconText(first.routeName),
                            sizePx = iconSizePx
                        )
                        rowViews.setImageViewBitmap(R.id.ivRouteIcon, bitmap)
                        rowViews.setTextViewText(R.id.tvHeadsign, first.headsign)

                        val timeCells = listOf(R.id.tvTime1, R.id.tvTime2, R.id.tvTime3)

                        val times = arrivals.map { arrival ->
                            val millisToArrival = arrival.arrivalTimestamp - now

                            when {
                                arrival.agency == Agency.BART && millisToArrival <= 0 -> "Leaving"
                                millisToArrival in 1..59_999 -> "Arriving"
                                else -> "${(millisToArrival / 60000).toInt()}min"
                            }
                        }

                        for (i in timeCells.indices) {
                            rowViews.setViewVisibility(
                                timeCells[i],
                                if (i < config.maxArrivals) View.VISIBLE else View.GONE
                            )
                        }

                        for (i in 0 until config.maxArrivals) {
                            val text = times.getOrNull(i) ?: "—"
                            val color = when (text) {
                                "Leaving" -> 0xFFdc3545.toInt()
                                "Arriving" -> 0xFF28a745.toInt()
                                "—" -> 0xFFBDC1C7.toInt()
                                else -> 0xFFFFD700.toInt()
                            }
                            rowViews.setTextViewText(timeCells[i], text)
                            rowViews.setTextColor(timeCells[i], color)
                        }

                        views.addView(R.id.llArrivals, rowViews)
                    }
                }

                if (overflow > 0) {
                    views.setTextViewText(
                        R.id.tvOverflowStatic,
                        "+$overflow more route${if (overflow > 1) "s" else ""}"
                    )
                } else {
                    views.setTextViewText(R.id.tvOverflowStatic, "")
                }

                appWidgetManager.updateAppWidget(widgetId, views)
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
                triggerFetch(context)
                updateWidget(context, AppWidgetManager.getInstance(context), widgetId)
            }
        }
    }
}
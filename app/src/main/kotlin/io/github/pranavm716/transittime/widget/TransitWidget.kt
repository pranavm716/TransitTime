package io.github.pranavm716.transittime.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
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
                        arrival.arrivalTimestamp > now &&
                                (config.filteredHeadsigns.isEmpty() ||
                                        arrival.headsign in config.filteredHeadsigns)
                    }
                    .groupBy { "${it.routeName}|${it.headsign}" }
                    .entries
                    .map { (_, arrivals) ->
                        arrivals.sortedBy { it.arrivalTimestamp }.take(config.maxArrivals)
                    }
                    .sortedBy { it.first().arrivalTimestamp }

                val totalGroups = allGroups.size
                val grouped = allGroups.take(maxRows)
                val overflow = totalGroups - maxRows

                val allArrivalsForStop = db.arrivalDao().getArrivalsForStop(config.stopId)
                val lastFetchedAt = allArrivalsForStop.maxOfOrNull { it.fetchedAt } ?: 0L
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
                        val bitmap = RouteIconDrawer.draw(first.agency, first.routeName, iconSizePx)
                        rowViews.setImageViewBitmap(R.id.ivRouteIcon, bitmap)

                        val timesText = arrivals.joinToString(", ") { arrival ->
                            val millisAway = arrival.arrivalTimestamp - now
                            val minutesAway = (millisAway / 60000).toInt()
                            when {
                                millisAway < 0 -> "Departed"
                                minutesAway < 1 -> "Arriving"
                                else -> "${minutesAway}min"
                            }
                        }

                        rowViews.setTextViewText(R.id.tvHeadsign, first.headsign)
                        rowViews.setTextViewText(R.id.tvMinutes, timesText)
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

                android.util.Log.d(
                    "TransitWidget",
                    "minHeight=$minHeight maxRows=$maxRows totalGroups=$totalGroups overflow=$overflow"
                )

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
package io.github.pranavm716.transittime.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.util.RouteColors
import io.github.pranavm716.transittime.util.RouteIconDrawer
import io.github.pranavm716.transittime.util.RouteShape
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

    companion object {
        const val ACTION_REFRESH = "io.github.pranavm716.transittime.ACTION_REFRESH"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Wire up refresh button tap
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

            // Read arrivals from DB and populate the view
            CoroutineScope(Dispatchers.IO).launch {
                val db = TransitDatabase.getInstance(context)
                val config = db.widgetConfigDao().getConfig(widgetId)

                if (config == null) {
                    views.setTextViewText(R.id.tvStopName, "Not configured")
                    appWidgetManager.updateAppWidget(widgetId, views)
                    return@launch
                }

                views.setTextViewText(R.id.tvStopName, config.stopName)

                // Set agency logo
                val logoRes = when (config.agency) {
                    Agency.BART -> R.drawable.ic_bart
                    Agency.MUNI -> R.drawable.ic_muni
                }
                views.setImageViewResource(R.id.ivAgencyLogo, logoRes)

                val now = System.currentTimeMillis()

                // Group by route+headsign, take N per group, sort by soonest arrival
                val grouped = db.arrivalDao()
                    .getArrivalsForStop(config.stopId)
                    .filter { arrival ->
                        arrival.arrivalTimestamp > now &&
                                (config.filteredHeadsigns.isEmpty() ||
                                        arrival.headsign in config.filteredHeadsigns)
                    }
                    .groupBy { "${it.routeName}|${it.headsign}" }
                    .mapValues { (_, arrivals) ->
                        arrivals.sortedBy { it.arrivalTimestamp }.take(config.maxArrivals)
                    }
                    .entries
                    .sortedBy { (_, arrivals) -> arrivals.first().arrivalTimestamp }

                // Freshness indicator
                val allArrivalsForStop = db.arrivalDao().getArrivalsForStop(config.stopId)
                val lastFetchedAt = allArrivalsForStop.maxOfOrNull { it.fetchedAt } ?: 0L

                val freshnessText = if (lastFetchedAt == 0L) {
                    "—"
                } else {
                    val formatter = SimpleDateFormat("h:mm:ss a ↻", Locale.getDefault())
                    formatter.format(Date(lastFetchedAt))
                }

                views.setTextViewText(R.id.tvFreshnessText, freshnessText)


                views.removeAllViews(R.id.llArrivals)
                for ((_, arrivals) in grouped) {
                    val first = arrivals.first()
                    val rowViews = RemoteViews(context.packageName, R.layout.widget_arrival_row)

                    // Draw route icon
                    val iconSizePx = (36 * context.resources.displayMetrics.density).toInt()
                    val label = RouteIconDrawer.getLabel(first.agency, first.routeName)
                    val isWide = first.agency == Agency.MUNI &&
                            RouteColors.getStyle(
                                first.agency,
                                first.routeName
                            ).shape == RouteShape.ROUNDED_RECT &&
                            label.length >= 3
                    val bitmap = if (isWide) {
                        RouteIconDrawer.drawWide(first.agency, first.routeName, iconSizePx)
                    } else {
                        RouteIconDrawer.draw(first.agency, first.routeName, iconSizePx)
                    }
                    rowViews.setImageViewBitmap(R.id.ivRouteIcon, bitmap)

                    // Times
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
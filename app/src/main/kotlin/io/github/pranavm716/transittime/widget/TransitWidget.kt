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
            views.setOnClickPendingIntent(R.id.tvRefresh, refreshPendingIntent)

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

                val now = System.currentTimeMillis()
                val arrivals = db.arrivalDao()
                    .getArrivalsForStop(config.stopId)
                    .filter { arrival ->
                        arrival.arrivalTimestamp > now &&
                                (config.filteredHeadsigns.isEmpty() ||
                                        arrival.headsign in config.filteredHeadsigns)
                    }
                    .take(config.maxArrivals)

                // Build arrival rows
                views.removeAllViews(R.id.llArrivals)
                for (arrival in arrivals) {
                    val rowViews = RemoteViews(context.packageName, R.layout.widget_arrival_row)
                    val millisAway = arrival.arrivalTimestamp - now
                    val minutesAway = (millisAway / 60000).toInt()
                    val secondsAway = ((millisAway % 60000) / 1000).toInt()
                    val minutesText = when {
                        millisAway < 0 -> "Departed"
                        minutesAway == 0 -> "${secondsAway}sec"
                        else -> "${minutesAway}min ${secondsAway}sec"
                    }
                    rowViews.setTextViewText(
                        R.id.tvHeadsign,
                        "${arrival.routeName} to ${arrival.headsign}"
                    )
                    rowViews.setTextViewText(R.id.tvMinutes, minutesText)
                    views.addView(R.id.llArrivals, rowViews)
                }

                // Update last refreshed timestamp
                val formatter = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
                views.setTextViewText(
                    R.id.tvLastUpdated,
                    "Updated ${formatter.format(Date(now))}"
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
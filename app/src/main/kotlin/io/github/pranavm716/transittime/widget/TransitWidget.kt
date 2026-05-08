package io.github.pranavm716.transittime.widget

import android.app.PendingIntent
import android.util.Log
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.TransitApplication
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.DelayColorMode
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.data.model.DisplayMode
import io.github.pranavm716.transittime.data.model.WidgetConfig
import io.github.pranavm716.transittime.transit.AgencyRegistry
import io.github.pranavm716.transittime.util.RouteIconDrawer
import io.github.pranavm716.transittime.util.getDelayColor
import io.github.pranavm716.transittime.util.groupDepartures
import io.github.pranavm716.transittime.wear.TileSnapshotPusher
import io.github.pranavm716.transittime.wear.buildSnapshot
import io.github.pranavm716.transittime.worker.FetchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class TransitWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            CoroutineScope(Dispatchers.IO).launch {
                updateWidget(context, appWidgetManager, widgetId)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val db = TransitDatabase.getInstance(context)
        CoroutineScope(Dispatchers.IO).launch {
            val configDao = db.widgetConfigDao()
            val departureDao = db.departureDao()
            val allConfigs = configDao.getAllConfigs()
            val deletedWidgetIds = appWidgetIds.toSet()
            val clearedStopIds = mutableSetOf<String>()
            for (widgetId in appWidgetIds) {
                val config = allConfigs.find { it.widgetId == widgetId } ?: continue
                configDao.deleteConfig(widgetId)
                val remaining =
                    allConfigs.filter { it.widgetId !in deletedWidgetIds && it.stopId == config.stopId }
                if (remaining.isEmpty()) {
                    departureDao.deleteDeparturesForStop(config.stopId)
                    clearedStopIds.add(config.stopId)
                }
            }
            val pusher = TileSnapshotPusher(context)
            for (stopId in clearedStopIds) {
                try {
                    Log.d("TransitWear", "TransitWidget.onDeleted: deleting snapshot for stopId=$stopId")
                    pusher.deleteSnapshot(stopId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                val remainingStopIds = configDao.getAllConfigs().map { it.stopId }.distinct()
                Log.d("TransitWear", "TransitWidget.onDeleted: pushing stop index, stopIds=$remainingStopIds")
                pusher.pushStopIndex(remainingStopIds)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.github.pranavm716.transittime.ACTION_REFRESH"
        const val ACTION_CYCLE_DISPLAY_MODE =
            "io.github.pranavm716.transittime.ACTION_CYCLE_DISPLAY_MODE"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        val COLOR_ON_TIME = 0xFFFFC107.toInt()
        val COLOR_LATE = 0xFFdc3545.toInt()
        val COLOR_EARLY = 0xFF28a745.toInt()

        fun lerp(from: Int, to: Int, t: Float): Int {
            val r =
                ((from shr 16 and 0xFF) + ((to shr 16 and 0xFF) - (from shr 16 and 0xFF)) * t).roundToInt()
            val g =
                ((from shr 8 and 0xFF) + ((to shr 8 and 0xFF) - (from shr 8 and 0xFF)) * t).roundToInt()
            val b = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * t).roundToInt()
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }

        private val spinningJobs = mutableMapOf<Int, Job>()
        private val spinStep = mutableMapOf<Int, Int>()

        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val rawHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val rawWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = if (rawHeight <= 110) 180 else rawHeight
            val minWidth = if (rawWidth <= 250) 360 else rawWidth

            val res = context.resources
            val dm = res.displayMetrics
            val headerDp = res.getDimension(R.dimen.widget_header_height) / dm.density
            val rowDp = res.getDimension(R.dimen.widget_row_height) / dm.density
            val padDp = res.getDimension(R.dimen.widget_body_vertical_overhead) / dm.density
            val maxRows = ((minHeight - headerDp - padDp) / rowDp).toInt().coerceAtLeast(1)

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
            views.setOnClickPendingIntent(R.id.llBody, refreshPendingIntent)

            val cycleModeIntent = Intent(context, TransitWidget::class.java).apply {
                action = ACTION_CYCLE_DISPLAY_MODE
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            val cycleModePendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId + 10_000,
                cycleModeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.llHeaderInfo, cycleModePendingIntent)
            views.setOnClickPendingIntent(R.id.llRefresh, refreshPendingIntent)

            withContext(Dispatchers.IO) {
                val db = TransitDatabase.getInstance(context)
                val config = db.widgetConfigDao().getConfig(widgetId) ?: run {
                    views.setTextViewText(R.id.tvStopName, "Not configured")
                    completeRevolution(context, appWidgetManager, widgetId)
                    appWidgetManager.updateAppWidget(widgetId, views)
                    return@withContext
                }

                val allDepartures = db.departureDao().getDeparturesForStop(config.stopId)
                val nowVal = System.currentTimeMillis()
                val (grouped, overflow) = groupDepartures(allDepartures, config.filteredHeadsigns, config.maxDepartures, nowVal, maxRows)

                val freshnessText = resolveFreshnessText(db, config)
                applyHeader(views, config, context, minWidth, freshnessText)
                applyFreshness(context, views, config, freshnessText)
                applyDepartures(context, views, grouped, config, nowVal)
                applyOverflow(views, overflow)

                completeRevolution(context, appWidgetManager, widgetId)
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun applyHeader(
            views: RemoteViews,
            config: WidgetConfig,
            context: Context,
            minWidthDp: Int,
            freshnessText: String
        ) {
            views.setTextViewText(R.id.tvStopName, config.stopName)
            val sp = calcStopNameTextSizeSp(context, config.stopName, minWidthDp, freshnessText)
            views.setTextViewTextSize(R.id.tvStopName, TypedValue.COMPLEX_UNIT_SP, sp)
            val logoRes = when (config.agency) {
                Agency.BART -> R.drawable.ic_bart
                Agency.MUNI -> R.drawable.ic_muni
                Agency.CALTRAIN -> R.drawable.ic_caltrain
            }
            views.setImageViewResource(R.id.ivAgencyLogo, logoRes)
        }

        private fun calcStopNameTextSizeSp(
            context: Context,
            stopName: String,
            widgetMinWidthDp: Int,
            freshnessText: String
        ): Float {
            val res = context.resources
            val dm = res.displayMetrics
            val freshnessPaint = android.graphics.Paint().apply {
                textSize = res.getDimension(R.dimen.widget_freshness_text_size)
            }
            val refreshAreaWidthPx = res.getDimension(R.dimen.widget_go_mode_padding_horizontal) +
                freshnessPaint.measureText(freshnessText) +
                res.getDimension(R.dimen.widget_freshness_text_margin_end) +
                res.getDimension(R.dimen.widget_freshness_icon_size) +
                res.getDimension(R.dimen.widget_freshness_icon_margin_end) +
                res.getDimension(R.dimen.widget_go_mode_padding_horizontal)
            val leftOverheadPx = res.getDimension(R.dimen.widget_header_padding_start) +
                res.getDimension(R.dimen.widget_agency_logo_width) +
                res.getDimension(R.dimen.widget_agency_logo_margin_end) +
                res.getDimension(R.dimen.widget_stop_name_margin_end)
            val availableWidthPx = widgetMinWidthDp * dm.density - refreshAreaWidthPx - leftOverheadPx
            val paint = android.graphics.Paint().apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            for (sp in floatArrayOf(21f, 20f, 19f, 18f, 17f, 16f, 15f, 14f, 13f)) {
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
                if (paint.measureText(stopName) <= availableWidthPx) return sp
            }
            return 13f
        }

        private suspend fun resolveFreshnessText(db: TransitDatabase, config: WidgetConfig): String {
            if (config.lastErrorLabel != null) return config.lastErrorLabel
            val lastFetchedAt = config.lastFetchedAt.takeIf { it > 0L }
                ?: db.departureDao().getDeparturesForStop(config.stopId)
                    .maxOfOrNull { it.fetchedAt } ?: 0L
            return if (lastFetchedAt == 0L) "—"
            else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(lastFetchedAt))
        }

        private fun applyFreshness(
            context: Context,
            views: RemoteViews,
            config: WidgetConfig,
            freshnessText: String
        ) {
            views.setViewVisibility(R.id.ivRefreshIcon, View.VISIBLE)
            views.setViewVisibility(R.id.tvFreshnessText, View.VISIBLE)
            views.setTextViewText(R.id.tvFreshnessText, freshnessText)
            views.setTextColor(
                R.id.tvFreshnessText,
                if (config.lastErrorLabel != null) COLOR_LATE else context.getColor(R.color.widget_color_secondary)
            )
        }

        private fun applyDepartures(
            context: Context,
            views: RemoteViews,
            grouped: List<List<Departure>>,
            config: WidgetConfig,
            now: Long
        ) {
            views.removeAllViews(R.id.llDepartures)
            views.removeAllViews(R.id.llEmptyContainer)

            if (grouped.isEmpty()) {
                views.addView(
                    R.id.llEmptyContainer,
                    RemoteViews(context.packageName, R.layout.widget_empty)
                )
                return
            }

            val allTimes = grouped.map { departures ->
                departures.map { departure ->
                    departure.getDisplayTime(now, config.displayMode, config.hybridThresholdMinutes)
                }
            }
            val timeFontSizeSp = calcTimeFontSizeSp(context, allTimes, config.maxDepartures)

            for ((departures, times) in grouped.zip(allTimes)) {
                views.addView(
                    R.id.llDepartures,
                    buildDepartureRow(
                        context,
                        departures,
                        times,
                        timeFontSizeSp,
                        config.maxDepartures,
                        config.delayColorMode
                    )
                )
            }
        }

        private fun buildDepartureRow(
            context: Context,
            departures: List<Departure>,
            times: List<String>,
            timeFontSizeSp: Float,
            maxDepartures: Int,
            delayColorMode: DelayColorMode
        ): RemoteViews {
            val first = departures.first()
            val handler = AgencyRegistry.get(first.agency)
            val rowViews = RemoteViews(context.packageName, R.layout.widget_departure_row)

            val iconSizePx = context.resources.getDimensionPixelSize(R.dimen.widget_departure_row_icon_size)
            val bitmap = RouteIconDrawer.draw(
                style = handler.getRouteStyle(first.routeName),
                text = handler.getIconText(first.routeName),
                sizePx = iconSizePx
            )
            rowViews.setImageViewBitmap(R.id.ivRouteIcon, bitmap)
            rowViews.setTextViewText(R.id.tvHeadsign, first.headsign)

            val timeCells = listOf(R.id.tvTime1, R.id.tvTime2, R.id.tvTime3)

            for (i in timeCells.indices) {
                rowViews.setViewVisibility(
                    timeCells[i],
                    if (i < maxDepartures) View.VISIBLE else View.GONE
                )
            }
            for (cell in timeCells) {
                rowViews.setTextViewTextSize(cell, TypedValue.COMPLEX_UNIT_SP, timeFontSizeSp)
            }
            for (i in 0 until maxDepartures) {
                val text = times.getOrNull(i) ?: "—"
                val departure = departures.getOrNull(i)
                val color = if (departure != null) getDelayColor(
                    departure.delaySeconds,
                    departure.isScheduled,
                    delayColorMode
                ) else context.getColor(R.color.widget_color_placeholder)
                rowViews.setTextViewText(timeCells[i], text)
                rowViews.setTextColor(timeCells[i], color)
            }
            return rowViews
        }

        private fun calcTimeFontSizeSp(
            context: Context,
            allTimes: List<List<String>>,
            maxDepartures: Int
        ): Float {
            val res = context.resources
            val cellWidthPx = res.getDimension(R.dimen.widget_departure_time_cell_width) -
                2 * res.getDimension(R.dimen.widget_departure_time_cell_padding_horizontal)
            val dm = res.displayMetrics
            val paint = android.graphics.Paint().apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            for (sp in floatArrayOf(16f, 15f, 14f)) {
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
                val maxWidth = allTimes.maxOfOrNull { times ->
                    (0 until maxDepartures).maxOfOrNull { i ->
                        paint.measureText(times.getOrNull(i) ?: "—")
                    } ?: 0f
                } ?: 0f
                if (maxWidth <= cellWidthPx) return sp
            }
            return 14f
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
            spinningJobs[widgetId]?.cancel()
            spinningJobs[widgetId] = CoroutineScope(Dispatchers.IO).launch {
                val steps = 12
                val stepAngle = 360f / steps
                while (isActive) {
                    val next = ((spinStep[widgetId] ?: 0) % steps) + 1
                    spinStep[widgetId] = next
                    val views = RemoteViews(context.packageName, R.layout.widget_layout)
                    views.setFloat(R.id.ivRefreshIcon, "setRotation", stepAngle * next)
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, views)
                    delay(40)
                }
            }
        }

        private suspend fun completeRevolution(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            spinningJobs.remove(widgetId)?.cancelAndJoin()
            val current = spinStep.remove(widgetId) ?: 0
            if (current in 1 until 24) {
                val steps = 12
                val stepAngle = 360f / steps
                for (s in (current + 1)..steps) {
                    val v = RemoteViews(context.packageName, R.layout.widget_layout)
                    v.setFloat(R.id.ivRefreshIcon, "setRotation", stepAngle * s)
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, v)
                    delay(40)
                }
            }
        }

        fun triggerFetch(context: Context) {
            Log.d("TransitWidget", "triggerFetch: enqueuing manual FetchWorker")
            val request = OneTimeWorkRequestBuilder<FetchWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                TransitApplication.FETCH_WORK_NAME_MANUAL,
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
                CoroutineScope(Dispatchers.IO).launch {
                    val db = TransitDatabase.getInstance(context)
                    db.widgetConfigDao().getConfig(widgetId) ?: return@launch
                    val allIds = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, TransitWidget::class.java)
                    )
                    for (id in allIds) {
                        animateRefreshIcon(context, appWidgetManager, id)
                    }
                    triggerFetch(context)
                }
            }
        } else if (intent.action == ACTION_CYCLE_DISPLAY_MODE) {
            val widgetId = intent.getIntExtra(
                EXTRA_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val db = TransitDatabase.getInstance(context)
                    val configDao = db.widgetConfigDao()
                    val config = configDao.getConfig(widgetId) ?: return@launch
                    val nextMode = when (config.displayMode) {
                        DisplayMode.RELATIVE -> DisplayMode.ABSOLUTE
                        DisplayMode.ABSOLUTE -> DisplayMode.HYBRID
                        DisplayMode.HYBRID -> DisplayMode.RELATIVE
                    }
                    configDao.upsertConfig(config.copy(displayMode = nextMode))
                    updateWidget(context, appWidgetManager, widgetId)
                    try {
                        val latestConfig = configDao.getConfig(widgetId)
                        if (latestConfig != null) {
                            val deps = db.departureDao().getDeparturesForStop(latestConfig.stopId)
                            val snapshot = buildSnapshot(
                                config = latestConfig,
                                departures = deps,
                                goModeActive = false,
                                goModeExpiresAt = 0L,
                                goModeTarget = false
                            )
                            TileSnapshotPusher(context).pushSnapshot(snapshot)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}

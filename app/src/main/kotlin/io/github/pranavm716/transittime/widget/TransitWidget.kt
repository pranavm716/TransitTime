package io.github.pranavm716.transittime.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import io.github.pranavm716.transittime.GoModeManager
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.TransitApplication
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.DelayColorMode
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.data.model.DisplayMode
import io.github.pranavm716.transittime.data.model.WidgetConfig
import io.github.pranavm716.transittime.gomode.InactiveStrategy
import io.github.pranavm716.transittime.service.GoModeNotificationService
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
import kotlin.math.sin

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
                    pusher.deleteSnapshot(stopId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                val remainingStopIds = configDao.getAllConfigs().map { it.stopId }.distinct()
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
        const val ACTION_TOGGLE_GO_MODE =
            "io.github.pranavm716.transittime.ACTION_TOGGLE_GO_MODE"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val GO_MODE_FETCH_WORK_NAME = "transit_go_mode_fetch"
        const val GO_MODE_EXPIRY_WORK_NAME = "transit_go_mode_expiry"

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
        private val pulsingJobs = mutableMapOf<Int, Job>()
        private val pulseStep = mutableMapOf<Int, Int>()

        fun updateWidgetAsync(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            skipAnimationCleanup: Boolean = false
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                updateWidget(context, appWidgetManager, widgetId, skipAnimationCleanup)
            }
        }

        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            skipAnimationCleanup: Boolean = false
        ) {
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val rawHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val rawWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = if (rawHeight <= 110) 200 else rawHeight
            val minWidth = if (rawWidth <= 250) 360 else rawWidth

            val res = context.resources
            val dm = res.displayMetrics
            val headerDp = res.getDimension(R.dimen.widget_header_height) / dm.density
            val rowDp = res.getDimension(R.dimen.widget_row_height) / dm.density
            val padDp = res.getDimension(R.dimen.widget_body_vertical_overhead) / dm.density
            val maxRows = ((minHeight - headerDp - padDp) / rowDp).toInt().coerceAtLeast(1)

            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            val goModeManager = GoModeManager(context)

            val refreshIntent = Intent(context, TransitWidget::class.java).apply {
                action = ACTION_REFRESH
                data = Uri.parse("transit://widget/$widgetId/refresh")
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
                data = Uri.parse("transit://widget/$widgetId/cycle")
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            val cycleModePendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId + 100_000,
                cycleModeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.llHeaderInfo, cycleModePendingIntent)

            withContext(Dispatchers.IO) {
                val db = TransitDatabase.getInstance(context)
                val config = db.widgetConfigDao().getConfig(widgetId) ?: run {
                    views.setTextViewText(R.id.tvStopName, "Not configured")
                    if (!skipAnimationCleanup) completeRevolution(
                        context,
                        appWidgetManager,
                        widgetId
                    )
                    appWidgetManager.updateAppWidget(widgetId, views)
                    return@withContext
                }

                val toggleGoModeIntent = Intent(context, TransitWidget::class.java).apply {
                    action = ACTION_TOGGLE_GO_MODE
                    data = Uri.parse("transit://widget/$widgetId/toggle")
                    putExtra(EXTRA_WIDGET_ID, widgetId)
                }
                val toggleGoModePendingIntent = PendingIntent.getBroadcast(
                    context,
                    widgetId + 20_000,
                    toggleGoModeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.llRefresh, toggleGoModePendingIntent)

                val allDepartures = db.departureDao().getDeparturesForStop(config.stopId)

                // Use the data's fetch time as the base for relative calculations to stay in sync with the watch.
                val lastFetchedAt = config.lastFetchedAt.takeIf { it > 0L }
                    ?: allDepartures.maxOfOrNull { it.fetchedAt } ?: 0L
                val baseTime = if (lastFetchedAt > 0) lastFetchedAt else System.currentTimeMillis()

                val (grouped, overflow) = groupDepartures(
                    allDepartures,
                    config.filteredHeadsigns,
                    config.maxDepartures,
                    baseTime,
                    maxRows
                )

                val freshnessText = if (config.lastErrorLabel != null) config.lastErrorLabel
                else if (lastFetchedAt == 0L) "—"
                else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(lastFetchedAt))

                applyHeader(views, config, context, minWidth, freshnessText)
                applyFreshness(context, views, config, freshnessText)
                applyDepartures(context, views, grouped, config, baseTime)
                applyOverflow(views, overflow)

                if (!skipAnimationCleanup) {
                    completeRevolution(context, appWidgetManager, widgetId)
                    completePulse(context, appWidgetManager, widgetId)
                }
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
            val availableWidthPx =
                widgetMinWidthDp * dm.density - refreshAreaWidthPx - leftOverheadPx
            val paint = android.graphics.Paint().apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            for (sp in floatArrayOf(21f, 20f, 19f, 18f, 17f, 16f, 15f, 14f, 13f)) {
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
                if (paint.measureText(stopName) <= availableWidthPx) return sp
            }
            return 13f
        }

        private fun applyFreshness(
            context: Context,
            views: RemoteViews,
            config: WidgetConfig,
            freshnessText: String
        ) {
            val goModeManager = GoModeManager(context)
            val strategy = goModeManager.getStrategyForWidget(config.widgetId)

            views.setViewVisibility(R.id.ivGoModeDot, strategy.dotVisibility)
            views.setViewVisibility(R.id.ivRefreshIcon, strategy.refreshIconVisibility)

            views.setViewVisibility(R.id.tvFreshnessText, View.VISIBLE)
            views.setTextViewText(R.id.tvFreshnessText, freshnessText)
            views.setTextColor(
                R.id.tvFreshnessText,
                strategy.getFreshnessColor(context, config.lastErrorLabel != null)
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

            val iconSizePx =
                context.resources.getDimensionPixelSize(R.dimen.widget_departure_row_icon_size)
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

        private const val ANIMATION_PERIOD_MS = 480L
        private const val REFRESH_STEPS = 12
        private const val PULSE_STEPS = 12

        fun animateRefreshIcon(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            hasError: Boolean = false
        ) {
            spinningJobs[widgetId]?.cancel()

            // Immediate partial update to sync UI state
            val initial = RemoteViews(context.packageName, R.layout.widget_layout)
            initial.setViewVisibility(R.id.ivGoModeDot, View.GONE)
            initial.setViewVisibility(R.id.ivRefreshIcon, View.VISIBLE)
            val color =
                if (hasError) 0xFFdc3545.toInt() else context.getColor(R.color.widget_color_secondary)
            initial.setTextColor(R.id.tvFreshnessText, color)

            val refreshIntent = Intent(context, TransitWidget::class.java).apply {
                action = ACTION_REFRESH
                data = Uri.parse("transit://widget/$widgetId/refresh")
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            initial.setOnClickPendingIntent(R.id.llBody, refreshPendingIntent)

            appWidgetManager.partiallyUpdateAppWidget(widgetId, initial)

            spinningJobs[widgetId] = CoroutineScope(Dispatchers.IO).launch {
                val stepAngle = 360f / REFRESH_STEPS
                val stepDelay = ANIMATION_PERIOD_MS / REFRESH_STEPS

                while (isActive) {
                    val current = (spinStep[widgetId] ?: 0)
                    val next = (current % REFRESH_STEPS) + 1
                    spinStep[widgetId] = next

                    val views = RemoteViews(context.packageName, R.layout.widget_layout)
                    views.setFloat(R.id.ivRefreshIcon, "setRotation", stepAngle * next)
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, views)

                    delay(stepDelay)
                }
            }
        }

        fun animateGoModeDot(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            hasError: Boolean = false
        ) {
            pulsingJobs[widgetId]?.cancel()

            // Immediate partial update to sync UI state
            val initial = RemoteViews(context.packageName, R.layout.widget_layout)
            initial.setViewVisibility(R.id.ivGoModeDot, View.VISIBLE)
            initial.setViewVisibility(R.id.ivRefreshIcon, View.GONE)
            val color = if (hasError) 0xFFdc3545.toInt() else context.getColor(R.color.accent_color)
            initial.setTextColor(R.id.tvFreshnessText, color)
            appWidgetManager.partiallyUpdateAppWidget(widgetId, initial)

            pulsingJobs[widgetId] = CoroutineScope(Dispatchers.IO).launch {
                val stepDelay = ANIMATION_PERIOD_MS / PULSE_STEPS

                while (isActive) {
                    for (i in 1..PULSE_STEPS) {
                        if (!isActive) break
                        pulseStep[widgetId] = i
                        val t = i.toFloat() / PULSE_STEPS
                        val scale = 1f + 0.5f * sin(Math.PI * t).toFloat()
                        val views = RemoteViews(context.packageName, R.layout.widget_layout)
                        views.setFloat(R.id.ivGoModeDot, "setScaleX", scale)
                        views.setFloat(R.id.ivGoModeDot, "setScaleY", scale)
                        appWidgetManager.partiallyUpdateAppWidget(widgetId, views)
                        delay(stepDelay)
                    }
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
            if (current in 1 until REFRESH_STEPS) {
                val stepAngle = 360f / REFRESH_STEPS
                val stepDelay = ANIMATION_PERIOD_MS / REFRESH_STEPS
                for (s in (current + 1)..REFRESH_STEPS) {
                    val v = RemoteViews(context.packageName, R.layout.widget_layout)
                    v.setFloat(R.id.ivRefreshIcon, "setRotation", stepAngle * s)
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, v)
                    delay(stepDelay)
                }
            }
        }

        private suspend fun completePulse(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            pulsingJobs.remove(widgetId)?.cancelAndJoin()
            val current = pulseStep.remove(widgetId) ?: return
            if (current in 1 until PULSE_STEPS) {
                val stepDelay = ANIMATION_PERIOD_MS / PULSE_STEPS
                for (i in (current + 1)..PULSE_STEPS) {
                    val t = i.toFloat() / PULSE_STEPS
                    val scale = 1f + 0.5f * sin(Math.PI * t).toFloat()
                    val v = RemoteViews(context.packageName, R.layout.widget_layout)
                    v.setFloat(R.id.ivGoModeDot, "setScaleX", scale)
                    v.setFloat(R.id.ivGoModeDot, "setScaleY", scale)
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, v)
                    delay(stepDelay)
                }
            }
        }

        fun updateGoModeStyle(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            active: Boolean,
            hasError: Boolean = false
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            if (active) {
                views.setViewVisibility(R.id.ivGoModeDot, View.VISIBLE)
                views.setViewVisibility(R.id.ivRefreshIcon, View.GONE)
                val color =
                    if (hasError) 0xFFdc3545.toInt() else context.getColor(R.color.accent_color)
                views.setTextColor(R.id.tvFreshnessText, color)
            } else {
                views.setViewVisibility(R.id.ivGoModeDot, View.GONE)
                views.setViewVisibility(R.id.ivRefreshIcon, View.VISIBLE)
                val color =
                    if (hasError) 0xFFdc3545.toInt() else context.getColor(R.color.widget_color_secondary)
                views.setTextColor(R.id.tvFreshnessText, color)

                val refreshIntent = Intent(context, TransitWidget::class.java).apply {
                    action = ACTION_REFRESH
                    data = Uri.parse("transit://widget/$widgetId/refresh")
                    putExtra(EXTRA_WIDGET_ID, widgetId)
                }
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.llBody, refreshPendingIntent)
            }
            appWidgetManager.partiallyUpdateAppWidget(widgetId, views)
        }

        fun triggerFetch(context: Context) {
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
        when (intent.action) {
            ACTION_REFRESH -> {
                val widgetId = intent.getIntExtra(
                    EXTRA_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val goModeManager = GoModeManager(context)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val activeGoModeWidgetId = goModeManager.goModeWidgetId
                    CoroutineScope(Dispatchers.IO).launch {
                        val db = TransitDatabase.getInstance(context)
                        val configDao = db.widgetConfigDao()
                        configDao.getConfig(widgetId) ?: return@launch
                        val allConfigs = configDao.getAllConfigs()
                        val allIds = appWidgetManager.getAppWidgetIds(
                            ComponentName(context, TransitWidget::class.java)
                        )
                        for (id in allIds) {
                            if (goModeManager.isGoModeActive && id == activeGoModeWidgetId) continue
                            val hasError =
                                allConfigs.find { it.widgetId == id }?.lastErrorLabel != null
                            InactiveStrategy().startAnimation(
                                context,
                                appWidgetManager,
                                id,
                                hasError
                            )
                        }
                        triggerFetch(context)
                    }
                }
            }

            ACTION_CYCLE_DISPLAY_MODE -> {
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
                        updateWidget(
                            context,
                            appWidgetManager,
                            widgetId,
                            skipAnimationCleanup = true
                        )
                        try {
                            val latestConfig = configDao.getConfig(widgetId)
                            if (latestConfig != null) {
                                val goModeManager = GoModeManager(context)
                                val deps =
                                    db.departureDao().getDeparturesForStop(latestConfig.stopId)
                                val isGlobalActive = goModeManager.isGoModeActive
                                val isTarget =
                                    isGlobalActive && latestConfig.widgetId == goModeManager.goModeWidgetId
                                val snapshot = buildSnapshot(
                                    config = latestConfig,
                                    departures = deps,
                                    goModeActive = isTarget,
                                    goModeExpiresAt = goModeManager.goModeExpiresAt,
                                    goModeTarget = isTarget
                                )
                                TileSnapshotPusher(context).pushSnapshot(snapshot)

                                if (isTarget) {
                                    GoModeNotificationService.update(context)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            ACTION_TOGGLE_GO_MODE -> {
                val widgetId =
                    intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                GoModeManager(context).toggle(widgetId)
            }

            else -> super.onReceive(context, intent)
        }
    }
}

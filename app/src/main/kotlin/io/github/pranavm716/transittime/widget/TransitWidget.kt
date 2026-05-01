package io.github.pranavm716.transittime.widget

import android.app.PendingIntent
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
import io.github.pranavm716.transittime.GoModeManager
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
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sin

class TransitWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val now = System.currentTimeMillis()
        for (widgetId in appWidgetIds) {
            CoroutineScope(Dispatchers.IO).launch {
                updateWidget(context, appWidgetManager, widgetId, now = now)
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
            for (widgetId in appWidgetIds) {
                val config = allConfigs.find { it.widgetId == widgetId } ?: continue
                configDao.deleteConfig(widgetId)
                val remaining =
                    allConfigs.filter { it.widgetId !in deletedWidgetIds && it.stopId == config.stopId }
                if (remaining.isEmpty()) {
                    departureDao.deleteDeparturesForStop(config.stopId)
                }
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
            updateWidget(context, appWidgetManager, appWidgetId, now = lastRenderNow[appWidgetId])
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

        private const val HEADER_DP = 44
        private const val ROW_DP = 40
        private const val PADDING_DP = 30

        private val lastRenderNow = mutableMapOf<Int, Long>()
        private val spinningJobs = mutableMapOf<Int, Job>()
        private val spinStep = mutableMapOf<Int, Int>()
        private val pulsingJobs = mutableMapOf<Int, Job>()
        private val pulseStep = mutableMapOf<Int, Int>()

        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            now: Long? = null
        ) {
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
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

            val toggleGoModeIntent = Intent(context, TransitWidget::class.java).apply {
                action = ACTION_TOGGLE_GO_MODE
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            val toggleGoModePendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId + 20_000,
                toggleGoModeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.llGoMode, toggleGoModePendingIntent)

            withContext(Dispatchers.IO) {
                val db = TransitDatabase.getInstance(context)
                val config = db.widgetConfigDao().getConfig(widgetId) ?: run {
                    views.setTextViewText(R.id.tvStopName, "Not configured")
                    completeRevolution(context, appWidgetManager, widgetId)
                    appWidgetManager.updateAppWidget(widgetId, views)
                    return@withContext
                }

                val nowVal = (now ?: System.currentTimeMillis()).also { lastRenderNow[widgetId] = it }
                val (grouped, overflow) = loadGroupedDepartures(db, config, nowVal, maxRows)

                applyHeader(views, config, context, minWidth)
                applyFreshness(context, views, db, config)
                applyDepartures(context, views, grouped, config, nowVal)
                applyOverflow(views, overflow)

                completeRevolution(context, appWidgetManager, widgetId)
                completePulse(context, appWidgetManager, widgetId)
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun applyHeader(
            views: RemoteViews,
            config: WidgetConfig,
            context: Context,
            minWidthDp: Int
        ) {
            views.setTextViewText(R.id.tvStopName, config.stopName)
            val sp = calcStopNameTextSizeSp(context, config.stopName, minWidthDp)
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
            widgetMinWidthDp: Int
        ): Float {
            val dm = context.resources.displayMetrics
            // paddingStart(16) + logo(42) + logoMarginEnd(10) + nameMarginEnd(5) + goModeArea(~90)
            val overheadDp = 163f
            val availableWidthPx = (widgetMinWidthDp - overheadDp) * dm.density
            val paint = android.graphics.Paint().apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            for (sp in floatArrayOf(21f, 20f, 19f, 18f, 17f, 16f, 15f, 14f, 13f)) {
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
                if (paint.measureText(stopName) <= availableWidthPx) return sp
            }
            return 13f
        }

        private suspend fun applyFreshness(
            context: Context,
            views: RemoteViews,
            db: TransitDatabase,
            config: WidgetConfig
        ) {
            val isGoModeActive = GoModeManager(context).isGoModeActive

            views.setViewVisibility(
                R.id.ivGoModeDot,
                if (isGoModeActive) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.ivRefreshIcon,
                if (isGoModeActive) View.GONE else View.VISIBLE
            )

            views.setViewVisibility(R.id.tvFreshnessText, View.VISIBLE)
            if (config.lastErrorLabel != null) {
                views.setTextViewText(R.id.tvFreshnessText, config.lastErrorLabel)
                views.setTextColor(R.id.tvFreshnessText, 0xFFdc3545.toInt()) // Red
            } else {
                val lastFetchedAt = config.lastFetchedAt.takeIf { it > 0L }
                    ?: db.departureDao().getDeparturesForStop(config.stopId)
                        .maxOfOrNull { it.fetchedAt } ?: 0L
                
                val freshnessText = if (lastFetchedAt == 0L) "—"
                else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(lastFetchedAt))

                views.setTextViewText(R.id.tvFreshnessText, freshnessText)
                views.setTextColor(
                    R.id.tvFreshnessText,
                    if (isGoModeActive) 0xFF238636.toInt() else 0xFFAAAAAA.toInt()
                )
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
                    (departure.departureTimestamp ?: departure.arrivalTimestamp
                    ?: Long.MIN_VALUE) > now &&
                            (config.filteredHeadsigns.isEmpty() ||
                                    "${departure.routeName}|${departure.headsign}" in config.filteredHeadsigns)
                }
                .groupBy { "${it.routeName}|${it.headsign}" }
                .entries
                .map { (_, departures) ->
                    departures.sortedBy {
                        it.arrivalTimestamp ?: it.departureTimestamp ?: Long.MAX_VALUE
                    }
                        .take(config.maxDepartures)
                }
                .sortedWith(
                    compareBy(
                        {
                            it.first().arrivalTimestamp ?: it.first().departureTimestamp
                            ?: Long.MAX_VALUE
                        },
                        {
                            it.getOrNull(1)?.let { d -> d.arrivalTimestamp ?: d.departureTimestamp }
                                ?: Long.MAX_VALUE
                        },
                        {
                            it.getOrNull(2)?.let { d -> d.arrivalTimestamp ?: d.departureTimestamp }
                                ?: Long.MAX_VALUE
                        },
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
                val color = if (departure != null) delayColor(
                    departure,
                    delayColorMode
                ) else 0xFFBDC1C7.toInt()
                rowViews.setTextViewText(timeCells[i], text)
                rowViews.setTextColor(timeCells[i], color)
            }
            return rowViews
        }

        private fun delayColor(
            departure: Departure,
            mode: DelayColorMode,
            onTimeColor: Int = COLOR_ON_TIME,
            lateColor: Int = COLOR_LATE,
            earlyColor: Int = COLOR_EARLY,
            lateDeadZoneSeconds: Int = 60,
            earlyDeadZoneSeconds: Int = 60,
            lateCapSeconds: Int = 300,
            earlyCapSeconds: Int = 180,
        ): Int {
            fun dimmed(color: Int): Int {
                val dim = 0.62f
                val r = ((color shr 16 and 0xFF) * dim).roundToInt()
                val g = ((color shr 8 and 0xFF) * dim).roundToInt()
                val b = ((color and 0xFF) * dim).roundToInt()
                return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }

            if (mode == DelayColorMode.NONE) {
                return if (departure.isScheduled) dimmed(onTimeColor) else onTimeColor
            }

            if (departure.isScheduled) return dimmed(onTimeColor)

            val delay = departure.delaySeconds
            if (delay == null || delay in -earlyDeadZoneSeconds..lateDeadZoneSeconds) return onTimeColor

            if (mode == DelayColorMode.FLAT) {
                return if (delay > lateDeadZoneSeconds) lateColor else earlyColor
            }

            // GRADIENT
            return if (delay > lateDeadZoneSeconds) {
                val t =
                    ((delay - lateDeadZoneSeconds).toFloat() / (lateCapSeconds - lateDeadZoneSeconds)).coerceIn(
                        0f,
                        1f
                    )
                lerp(onTimeColor, lateColor, t)
            } else {
                val t =
                    ((-delay - earlyDeadZoneSeconds).toFloat() / (earlyCapSeconds - earlyDeadZoneSeconds)).coerceIn(
                        0f,
                        1f
                    )
                lerp(onTimeColor, earlyColor, t)
            }
        }

        private fun calcTimeFontSizeSp(
            context: Context,
            allTimes: List<List<String>>,
            maxDepartures: Int,
            cellWidthDp: Float = 52f
        ): Float {
            val dm = context.resources.displayMetrics
            val cellWidthPx = cellWidthDp * dm.density
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

        fun animateGoModeDot(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            pulsingJobs[widgetId]?.cancel()
            pulsingJobs[widgetId] = CoroutineScope(Dispatchers.IO).launch {
                val steps = 11
                while (isActive) {
                    for (i in 0..steps) {
                        if (!isActive) break
                        pulseStep[widgetId] = i
                        val t = i.toFloat() / steps
                        val scale = 1f + 0.5f * sin(Math.PI * t).toFloat()
                        val views = RemoteViews(context.packageName, R.layout.widget_layout)
                        views.setFloat(R.id.ivGoModeDot, "setScaleX", scale)
                        views.setFloat(R.id.ivGoModeDot, "setScaleY", scale)
                        appWidgetManager.partiallyUpdateAppWidget(widgetId, views)
                        delay(40)
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

        private suspend fun completePulse(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            pulsingJobs.remove(widgetId)?.cancelAndJoin()
            val current = pulseStep.remove(widgetId) ?: return
            if (current > 0) {
                val steps = 11
                for (i in (current + 1)..steps) {
                    val t = i.toFloat() / steps
                    val scale = 1f + 0.5f * sin(Math.PI * t).toFloat()
                    val v = RemoteViews(context.packageName, R.layout.widget_layout)
                    v.setFloat(R.id.ivGoModeDot, "setScaleX", scale)
                    v.setFloat(R.id.ivGoModeDot, "setScaleY", scale)
                    appWidgetManager.partiallyUpdateAppWidget(widgetId, v)
                    delay(40)
                }
            }
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
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val widgetId = intent.getIntExtra(
                EXTRA_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                if (GoModeManager(context).isGoModeActive) return
                val appWidgetManager = AppWidgetManager.getInstance(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val db = TransitDatabase.getInstance(context)
                    db.widgetConfigDao().getConfig(widgetId) ?: return@launch
                    animateRefreshIcon(context, appWidgetManager, widgetId)
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
                    updateWidget(context, appWidgetManager, widgetId, now = lastRenderNow[widgetId])
                }
            }
        } else if (intent.action == ACTION_TOGGLE_GO_MODE) {
            val goModeManager = GoModeManager(context)
            if (goModeManager.isGoModeActive) {
                goModeManager.goModeExpiresAt = 0
            } else {
                goModeManager.goModeExpiresAt =
                    System.currentTimeMillis() + GoModeManager.GO_MODE_DURATION_MS
            }
            if (goModeManager.isGoModeActive) {
                triggerFetch(context)
                WorkManager.getInstance(context).enqueueUniqueWork(
                    GO_MODE_FETCH_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<FetchWorker>()
                        .setInitialDelay(GoModeManager.GO_MODE_INTERVAL_MS, TimeUnit.MILLISECONDS)
                        .build()
                )
                WorkManager.getInstance(context).enqueueUniqueWork(
                    GO_MODE_EXPIRY_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<FetchWorker>()
                        .setInitialDelay(GoModeManager.GO_MODE_DURATION_MS, TimeUnit.MILLISECONDS)
                        .build()
                )
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(GO_MODE_FETCH_WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork(GO_MODE_EXPIRY_WORK_NAME)
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, TransitWidget::class.java)
            )
            val now = System.currentTimeMillis()
            for (widgetId in ids) {
                CoroutineScope(Dispatchers.IO).launch {
                    updateWidget(
                        context, appWidgetManager, widgetId,
                        now = now
                    )
                }
            }
        }
    }
}
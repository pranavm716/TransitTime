package io.github.pranavm716.transittime.transit.caltrain

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import io.github.pranavm716.transittime.BuildConfig
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Arrival
import io.github.pranavm716.transittime.data.model.DisplayMode
import io.github.pranavm716.transittime.transit.TransitAgency
import io.github.pranavm716.transittime.transit.formatArrivalTime
import io.github.pranavm716.transittime.util.RouteShape
import io.github.pranavm716.transittime.util.RouteStyle

object CaltrainAgency : TransitAgency {
    override val agency = Agency.CALTRAIN

    override suspend fun loadStaticData(context: Context) {
        CaltrainParser.loadStaticGtfs(context)
    }

    override fun getStopNames(): Map<String, String> = CaltrainParser.getStopNames()

    override suspend fun fetchArrivals(stopIds: Set<String>, fetchedAt: Long): List<Arrival> {
        val bytes = CaltrainApiClient.api.getTripUpdates(apiKey = BuildConfig.MUNI_API_KEY).bytes()
        val rtArrivals = CaltrainParser.parseRtFeed(bytes, fetchedAt).filter { it.stopId in stopIds }

        val activeServices = CaltrainParser.getActiveServices()
        val result = mutableListOf<Arrival>()
        for (stopId in stopIds) {
            val rtForStop = rtArrivals.filter { it.stopId == stopId }
            val scheduled = CaltrainParser.getScheduledDepartures(stopId, activeServices)
            result.addAll(
                mergeWithTimetable(
                    rtArrivals = rtForStop,
                    scheduledDepartures = scheduled,
                    maxArrivals = 3,
                    now = fetchedAt,
                    stopId = stopId,
                    fetchedAt = fetchedAt
                )
            )
        }
        return result
    }

    override suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>> =
        CaltrainParser.getRoutesForStation(stopId)

    override fun getRouteStyle(routeName: String): RouteStyle = caltrainGetStyle(routeName)

    override fun getIconText(routeName: String): String = caltrainGetIconText(routeName)

    override fun getArrivalDisplayTime(arrival: Arrival, now: Long, displayMode: DisplayMode, hybridThresholdMinutes: Int): String {
        val millisToArrival = arrival.arrivalTimestamp - now
        val millisToDeparture = arrival.departureTimestamp - now
        return when {
            arrival.arrivalTimestamp == arrival.departureTimestamp &&
                    millisToDeparture in 0..60_000 -> "Leaving"
            millisToArrival <= 0 && millisToDeparture in 0..60_000 -> "Leaving"
            millisToArrival in 1..59_999 -> "Arriving"
            else -> formatArrivalTime(arrival.departureTimestamp, millisToDeparture, displayMode, hybridThresholdMinutes)
        }
    }
}

private val DARK_TEXT = "#222222".toColorInt()

private fun caltrainGetStyle(routeName: String): RouteStyle {
    val (bg, text) = when {
        routeName.contains("Express", ignoreCase = true) ||
                routeName.contains("Bullet", ignoreCase = true) ->
            "#da1931".toColorInt() to Color.WHITE

        routeName.contains("Limited", ignoreCase = true) ->
            "#97d7dd".toColorInt() to DARK_TEXT

        routeName.contains("South County", ignoreCase = true) ->
            "#fbf1d2".toColorInt() to DARK_TEXT

        routeName.contains("Local", ignoreCase = true) ->
            Color.WHITE to DARK_TEXT

        else -> Color.WHITE to DARK_TEXT
    }
    return RouteStyle(bg, text, RouteShape.RECT)
}

private fun caltrainGetIconText(routeName: String): String = when {
    routeName.contains("Express", ignoreCase = true) ||
            routeName.contains("Bullet", ignoreCase = true) -> "EXP"

    routeName.contains("Limited", ignoreCase = true) -> "LTD"

    routeName.contains("South County", ignoreCase = true) -> "SCC"

    routeName.contains("Local", ignoreCase = true) -> "LOC"

    else -> "LOC"
}

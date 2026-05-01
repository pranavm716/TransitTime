package io.github.pranavm716.transittime.transit.caltrain

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import io.github.pranavm716.transittime.BuildConfig
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.transit.FetchResult
import io.github.pranavm716.transittime.transit.TransitAgency
import io.github.pranavm716.transittime.util.RouteShape
import io.github.pranavm716.transittime.util.RouteStyle

import io.github.pranavm716.transittime.transit.AuthenticationException
import io.github.pranavm716.transittime.transit.RateLimitException
import io.github.pranavm716.transittime.transit.TransitServerException
import java.io.IOException

object CaltrainAgency : TransitAgency {
    override val agency = Agency.CALTRAIN

    override suspend fun loadStaticData(context: Context) {
        CaltrainParser.loadStaticGtfs(context)
    }

    override fun getStopNames(): Map<String, String> = CaltrainParser.getStopNames()

    override suspend fun fetchDepartures(stopIds: Set<String>, fetchedAt: Long): FetchResult {
        val response = CaltrainApiClient.api.getTripUpdates(apiKey = BuildConfig.TRANSIT511_API_KEY)
        if (!response.isSuccessful) {
            val code = response.code()
            val msg = response.message()
            throw when (code) {
                401, 403 -> AuthenticationException("Caltrain API Auth error: $code $msg")
                429 -> RateLimitException("Caltrain API Rate limit error")
                in 500..599 -> TransitServerException("Caltrain API Server error: $code $msg")
                else -> IOException("Caltrain API error: $code $msg")
            }
        }
        val bytes = response.body()?.bytes() ?: throw IOException("Empty response body")
        val rtDepartures = CaltrainParser.parseRtFeed(bytes, fetchedAt).filter { it.stopId in stopIds }

        val allRtTripIds = rtDepartures.mapNotNull { it.tripId }.toSet()
        val activeServices = CaltrainParser.getActiveServices()
        val tripTerminals = CaltrainParser.getTripTerminals()
        val tripOrigins = CaltrainParser.getTripOrigins()
        val result = mutableListOf<Departure>()
        for (stopId in stopIds) {
            val rtForStop = rtDepartures.filter { it.stopId == stopId }
            val scheduled = CaltrainParser.getScheduledDepartures(stopId, activeServices)
            result.addAll(
                mergeWithTimetable(
                    rtDepartures = rtForStop,
                    scheduledDepartures = scheduled,
                    maxDepartures = 3,
                    now = fetchedAt,
                    stopId = stopId,
                    fetchedAt = fetchedAt,
                    tripTerminals = tripTerminals,
                    tripOrigins = tripOrigins,
                    allRtTripIds = allRtTripIds
                )
            )
        }
        return FetchResult(result, emptyMap())
    }

    override suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>> =
        CaltrainParser.getRoutesForStation(stopId)

    override fun getRouteStyle(routeName: String): RouteStyle = caltrainGetStyle(routeName)

    override fun getIconText(routeName: String): String = caltrainGetIconText(routeName)

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

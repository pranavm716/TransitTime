package io.github.pranavm716.transittime.transit.muni

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.transit.FetchResult
import io.github.pranavm716.transittime.transit.TransitAgency
import io.github.pranavm716.transittime.util.RouteShape
import io.github.pranavm716.transittime.util.RouteStyle

object MuniAgency : TransitAgency {
    override val agency = Agency.MUNI

    override suspend fun loadStaticData(context: Context) {
        MuniParser.loadStaticGtfs(context)
    }

    override fun getStopNames(): Map<String, String> = MuniParser.getStopNames()

    override suspend fun fetchDepartures(stopIds: Set<String>, fetchedAt: Long): FetchResult {
        val departures = mutableListOf<Departure>()
        val stopErrors = mutableMapOf<String, Exception>()
        for (stopId in stopIds) {
            try {
                departures.addAll(MuniParser.fetchAndParseStop(stopId, fetchedAt))
            } catch (e: Exception) {
                e.printStackTrace()
                stopErrors[stopId] = e
            }
        }
        return FetchResult(departures, stopErrors)
    }

    override suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>> =
        MuniParser.fetchRoutesForStop(stopId)

    override fun getRouteStyle(routeName: String): RouteStyle = muniGetStyle(routeName)

    override fun getIconText(routeName: String): String = routeName.uppercase()

}

private fun muniGetStyle(routeName: String): RouteStyle {
    val metroColor = when (routeName.uppercase()) {
        "J" -> "#a96614".toColorInt()
        "K", "KT" -> "#437c93".toColorInt()
        "L" -> "#942d83".toColorInt()
        "M" -> "#008547".toColorInt()
        "N" -> "#005b95".toColorInt()
        "T" -> "#bf2b45".toColorInt()
        "E", "F" -> "#b49a36".toColorInt()
        else -> null
    }
    if (metroColor != null) {
        return RouteStyle(metroColor, Color.WHITE, RouteShape.CIRCLE)
    }

    val metroSubColor = when (routeName.uppercase()) {
        "KBUS" -> "#437c93".toColorInt()
        "LBUS" -> "#942d83".toColorInt()
        "NBUS" -> "#005b95".toColorInt()
        "TBUS" -> "#bf2b45".toColorInt()
        "FBUS" -> "#b49a36".toColorInt()
        else -> null
    }
    if (metroSubColor != null) {
        return RouteStyle(metroSubColor, Color.WHITE, RouteShape.ROUNDED_RECT)
    }

    if (routeName.endsWith("OWL", ignoreCase = true) ||
        routeName.uppercase() in listOf("90", "91")
    ) {
        return RouteStyle("#666666".toColorInt(), Color.WHITE, RouteShape.ROUNDED_RECT)
    }

    if (routeName.uppercase() in listOf("PH", "PM", "CA")) {
        return RouteStyle("#8B4513".toColorInt(), Color.WHITE, RouteShape.ROUNDED_RECT)
    }

    val isRapid = routeName.endsWith("R", ignoreCase = true) ||
            routeName.endsWith("X", ignoreCase = true)
    val busColor = if (isRapid) "#bf2b45".toColorInt() else "#005b95".toColorInt()
    return RouteStyle(busColor, Color.WHITE, RouteShape.ROUNDED_RECT)
}

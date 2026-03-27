package io.github.pranavm716.transittime.transit.bart

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Arrival
import io.github.pranavm716.transittime.transit.TransitAgency
import io.github.pranavm716.transittime.util.RouteShape
import io.github.pranavm716.transittime.util.RouteStyle

object BartAgency : TransitAgency {
    override val agency = Agency.BART

    override suspend fun loadStaticData(context: Context) {
        BartParser.loadStaticGtfs(context)
    }

    override fun getStopNames(): Map<String, String> = BartParser.getStopNames()

    override suspend fun fetchArrivals(stopIds: Set<String>, fetchedAt: Long): List<Arrival> {
        val bytes = BartApiClient.api.getTripUpdates().bytes()
        return BartParser.parseRtFeed(bytes, fetchedAt).filter { it.stopId in stopIds }
    }

    override suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>> =
        BartParser.fetchRoutesForStop(stopId)

    override fun getRouteStyle(routeName: String): RouteStyle = bartGetStyle(routeName)

    override fun getIconText(routeName: String): String = bartGetIconText(routeName)
}

private val DARK_TEXT = "#222222".toColorInt()

private fun bartGetStyle(routeName: String): RouteStyle {
    val color = when {
        routeName.contains("Red", ignoreCase = true) -> "#ed1c24".toColorInt()
        routeName.contains("Yellow", ignoreCase = true) -> "#ffe600".toColorInt()
        routeName.contains("Blue", ignoreCase = true) -> "#00a6e9".toColorInt()
        routeName.contains("Green", ignoreCase = true) -> "#50b848".toColorInt()
        routeName.contains("Orange", ignoreCase = true) -> "#faa61a".toColorInt()
        else -> Color.GRAY
    }
    val textColor =
        if (routeName.contains("Yellow", ignoreCase = true)) DARK_TEXT else Color.WHITE
    return RouteStyle(color, textColor, RouteShape.SQUARE)
}

private fun bartGetIconText(routeName: String): String = when {
    routeName.contains("Red", ignoreCase = true) -> "R"
    routeName.contains("Yellow", ignoreCase = true) -> "Y"
    routeName.contains("Blue", ignoreCase = true) -> "B"
    routeName.contains("Green", ignoreCase = true) -> "G"
    routeName.contains("Orange", ignoreCase = true) -> "O"
    else -> "?"
}

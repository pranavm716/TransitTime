package io.github.pranavm716.transittime.transit

import android.content.Context
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.data.model.DisplayMode
import io.github.pranavm716.transittime.util.RouteStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface TransitAgency {
    val agency: Agency

    suspend fun loadStaticData(context: Context)
    fun getStopNames(): Map<String, String>
    suspend fun fetchArrivals(stopIds: Set<String>, fetchedAt: Long): List<Departure>
    suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>>
    fun getRouteStyle(routeName: String): RouteStyle
    fun getIconText(routeName: String): String
    fun getDisplayTime(departure: Departure, now: Long): String
}

fun formatArrivalTime(
    relevantTimestamp: Long,
    millisUntilRelevant: Long,
    displayMode: DisplayMode,
    hybridThresholdMinutes: Int
): String {
    val minutes = (millisUntilRelevant / 60000).toInt()
    val absoluteTime = SimpleDateFormat("h:mma", Locale.getDefault()).format(Date(relevantTimestamp))
    val relativeTime = "${minutes}min"
    return when (displayMode) {
        DisplayMode.RELATIVE -> relativeTime
        DisplayMode.ABSOLUTE -> absoluteTime
        DisplayMode.HYBRID -> if (minutes < hybridThresholdMinutes) relativeTime else absoluteTime
    }
}

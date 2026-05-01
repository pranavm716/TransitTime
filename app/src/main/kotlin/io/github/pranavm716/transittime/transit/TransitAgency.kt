package io.github.pranavm716.transittime.transit

import android.content.Context
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.util.RouteStyle

data class FetchResult(
    val departures: List<Departure>,
    val stopErrors: Map<String, Exception>
)

interface TransitAgency {
    val agency: Agency

    suspend fun loadStaticData(context: Context)
    fun getStopNames(): Map<String, String>
    suspend fun fetchDepartures(stopIds: Set<String>, fetchedAt: Long): FetchResult
    suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>>
    fun getRouteStyle(routeName: String): RouteStyle
    fun getIconText(routeName: String): String
}

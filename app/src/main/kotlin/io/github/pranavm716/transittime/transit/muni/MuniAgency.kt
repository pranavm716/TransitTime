package io.github.pranavm716.transittime.transit.muni

import android.content.Context
import io.github.pranavm716.transittime.data.api.muni.MuniParser
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Arrival
import io.github.pranavm716.transittime.transit.TransitAgency
import io.github.pranavm716.transittime.util.RouteStyle

object MuniAgency : TransitAgency {
    override val agency = Agency.MUNI

    override suspend fun loadStaticData(context: Context) {
        MuniParser.loadStaticGtfs(context)
    }

    override fun getStopNames(): Map<String, String> = MuniParser.getStopNames()

    override suspend fun fetchArrivals(stopIds: Set<String>, fetchedAt: Long): List<Arrival> {
        val arrivals = mutableListOf<Arrival>()
        for (stopId in stopIds) {
            arrivals.addAll(MuniParser.fetchAndParseStop(stopId, fetchedAt))
        }
        return arrivals
    }

    override suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>> =
        MuniParser.fetchRoutesForStop(stopId)

    override fun getRouteStyle(routeName: String): RouteStyle = MuniRouteStyle.getStyle(routeName)

    override fun getIconText(routeName: String): String = MuniRouteStyle.getIconText(routeName)
}

package io.github.pranavm716.transittime.transit.bart

import android.content.Context
import io.github.pranavm716.transittime.data.api.bart.BartApiClient
import io.github.pranavm716.transittime.data.api.bart.BartParser
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Arrival
import io.github.pranavm716.transittime.transit.TransitAgency
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

    override fun getRouteStyle(routeName: String): RouteStyle = BartRouteStyle.getStyle(routeName)

    override fun getIconText(routeName: String): String = BartRouteStyle.getIconText(routeName)
}

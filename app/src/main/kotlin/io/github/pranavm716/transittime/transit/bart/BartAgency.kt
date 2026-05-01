package io.github.pranavm716.transittime.transit.bart

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.transit.TransitAgency
import io.github.pranavm716.transittime.util.RouteShape
import io.github.pranavm716.transittime.util.RouteStyle

import io.github.pranavm716.transittime.transit.AuthenticationException
import io.github.pranavm716.transittime.transit.RateLimitException
import io.github.pranavm716.transittime.transit.TransitServerException
import retrofit2.HttpException
import java.io.IOException

object BartAgency : TransitAgency {
    override val agency = Agency.BART

    override suspend fun loadStaticData(context: Context) {
        BartParser.loadStaticGtfs(context)
    }

    override fun getStopNames(): Map<String, String> = BartParser.getStopNames()

    override suspend fun fetchDepartures(stopIds: Set<String>, fetchedAt: Long): List<Departure> {
        val response = BartApiClient.api.getTripUpdates()
        if (!response.isSuccessful) {
            val code = response.code()
            val msg = response.message()
            throw when (code) {
                401, 403 -> AuthenticationException("BART API Auth error: $code $msg")
                429 -> RateLimitException("BART API Rate limit error")
                in 500..599 -> TransitServerException("BART API Server error: $code $msg")
                else -> IOException("BART API error: $code $msg")
            }
        }
        
        val bytes = response.body()?.bytes() ?: throw IOException("Empty response body")
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

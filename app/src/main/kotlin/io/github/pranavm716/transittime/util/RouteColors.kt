package io.github.pranavm716.transittime.util

import android.graphics.Color
import androidx.core.graphics.toColorInt
import io.github.pranavm716.transittime.data.model.Agency

data class RouteStyle(
    val backgroundColor: Int,
    val textColor: Int,
    val shape: RouteShape
)

enum class RouteShape { SQUARE, CIRCLE, ROUNDED_RECT }

object RouteColors {

    private val DARK_TEXT = "#222222".toColorInt()
    private const val WHITE_TEXT = Color.WHITE

    fun getStyle(agency: Agency, routeName: String): RouteStyle {
        return when (agency) {
            Agency.BART -> getBartStyle(routeName)
            Agency.MUNI -> getMuniStyle(routeName)
        }
    }

    private fun getBartStyle(routeName: String): RouteStyle {
        val color = when {
            routeName.contains("Red", ignoreCase = true) -> "#ed1c24".toColorInt()
            routeName.contains("Yellow", ignoreCase = true) -> "#ffe600".toColorInt()
            routeName.contains("Blue", ignoreCase = true) -> "#00a6e9".toColorInt()
            routeName.contains("Green", ignoreCase = true) -> "#50b848".toColorInt()
            routeName.contains("Orange", ignoreCase = true) -> "#faa61a".toColorInt()
            else -> Color.GRAY
        }
        val textColor =
            if (routeName.contains("Yellow", ignoreCase = true)) DARK_TEXT else WHITE_TEXT
        return RouteStyle(color, textColor, RouteShape.SQUARE)
    }

    private fun getMuniStyle(routeName: String): RouteStyle {
        val metroColor = when (routeName.uppercase()) {
            "J" -> "#a96614".toColorInt()
            "K" -> "#437c93".toColorInt()
            "L" -> "#942d83".toColorInt()
            "M" -> "#008547".toColorInt()
            "N" -> "#005b95".toColorInt()
            "T" -> "#bf2b45".toColorInt()
            else -> null
        }
        if (metroColor != null) {
            return RouteStyle(metroColor, WHITE_TEXT, RouteShape.CIRCLE)
        }

        val isRapid = routeName.endsWith("R", ignoreCase = true)
        val busColor = if (isRapid) "#bf2b45".toColorInt() else "#005b95".toColorInt()
        return RouteStyle(busColor, WHITE_TEXT, RouteShape.ROUNDED_RECT)
    }
}
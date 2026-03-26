package io.github.pranavm716.transittime.transit.bart

import android.graphics.Color
import androidx.core.graphics.toColorInt
import io.github.pranavm716.transittime.util.RouteShape
import io.github.pranavm716.transittime.util.RouteStyle

object BartRouteStyle {

    private val DARK_TEXT = "#222222".toColorInt()

    fun getStyle(routeName: String): RouteStyle {
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

    fun getIconText(routeName: String): String = when {
        routeName.contains("Red", ignoreCase = true) -> "R"
        routeName.contains("Yellow", ignoreCase = true) -> "Y"
        routeName.contains("Blue", ignoreCase = true) -> "B"
        routeName.contains("Green", ignoreCase = true) -> "G"
        routeName.contains("Orange", ignoreCase = true) -> "O"
        else -> "?"
    }
}

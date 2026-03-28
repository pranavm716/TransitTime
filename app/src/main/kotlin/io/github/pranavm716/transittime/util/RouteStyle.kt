package io.github.pranavm716.transittime.util

data class RouteStyle(
    val backgroundColor: Int,
    val textColor: Int,
    val shape: RouteShape
)

enum class RouteShape { SQUARE, CIRCLE, ROUNDED_RECT, RECT }

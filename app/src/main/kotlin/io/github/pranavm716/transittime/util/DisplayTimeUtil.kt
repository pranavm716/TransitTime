package io.github.pranavm716.transittime.util

import io.github.pranavm716.transittime.data.model.DisplayMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun getDisplayTime(
    arrivalTimestamp: Long?,
    departureTimestamp: Long?,
    isOriginStop: Boolean,
    isScheduled: Boolean,
    now: Long,
    displayMode: DisplayMode = DisplayMode.RELATIVE,
    hybridThresholdMinutes: Int = 60,
    departingWindowMillis: Long = 30_000
): String {
    val millisToArrival = arrivalTimestamp?.minus(now)
    val millisToDeparture = departureTimestamp?.minus(now)
    val millisToDisplay = millisToDeparture ?: millisToArrival ?: return ""

    // Scheduled departures always show Xmin — never Arriving or Leaving
    if (!isScheduled) {
        // Leaving — origin stop about to depart
        if (isOriginStop && millisToDeparture != null && millisToDeparture in 0..60_000) {
            return "Leaving"
        }

        // Leaving — train at platform, departure imminent
        if (!isOriginStop &&
            millisToArrival != null && millisToArrival <= 0 &&
            millisToDeparture != null && millisToDeparture in 0..60_000
        ) {
            return "Leaving"
        }

        // Arriving — train within arriving window, has arrival data, not an origin stop
        if (!isOriginStop && millisToArrival != null && millisToArrival in 1..departingWindowMillis) {
            return "Arriving"
        }
    }

    val minutes = maxOf(1, (millisToDisplay / 60_000).toInt())
    val relativeTime = "${minutes}min"
    val absoluteTime = SimpleDateFormat("h:mma", Locale.getDefault())
        .format(Date(departureTimestamp ?: arrivalTimestamp!!))
    return when (displayMode) {
        DisplayMode.RELATIVE -> relativeTime
        DisplayMode.ABSOLUTE -> absoluteTime
        DisplayMode.HYBRID -> if (minutes < hybridThresholdMinutes) relativeTime else absoluteTime
    }
}

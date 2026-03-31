package io.github.pranavm716.transittime.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "departures")
data class Departure(
    @PrimaryKey val id: String,
    val stopId: String,
    val routeName: String,
    val headsign: String,
    val agency: Agency,
    val arrivalTimestamp: Long?,      // null if not provided by API
    val departureTimestamp: Long?,    // null if not provided by API
    val isOriginStop: Boolean = false,
    val isScheduled: Boolean,
    val tripId: String?,
    val fetchedAt: Long
) {
    fun getDisplayTime(
        now: Long,
        displayMode: DisplayMode = DisplayMode.RELATIVE,
        hybridThresholdMinutes: Int = 60,
        arrivingWindowMillis: Long = 30_000
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
            if (!isOriginStop && millisToArrival != null && millisToArrival in 1..arrivingWindowMillis) {
                return "Arriving"
            }
        }

        // Xmin or absolute time depending on display mode
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
}

enum class Agency {
    BART, MUNI, CALTRAIN
}

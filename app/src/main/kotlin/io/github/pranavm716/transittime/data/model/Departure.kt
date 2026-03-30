package io.github.pranavm716.transittime.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "departures")
data class Departure(
    @PrimaryKey val id: String,
    val stopId: String,
    val routeName: String,
    val headsign: String,
    val agency: Agency,
    val arrivalTimestamp: Long?,      // null if not provided by API
    val departureTimestamp: Long?,    // null if not provided by API
    val isTerminalStop: Boolean,
    val fetchedAt: Long
) {
    fun getDisplayTime(now: Long): String {
        val isSched = id.endsWith("_sched")
        val millisToArrival = arrivalTimestamp?.minus(now)
        val millisToDeparture = departureTimestamp?.minus(now)
        val millisToDisplay = millisToDeparture ?: millisToArrival ?: return ""

        // Scheduled departures always show Xmin — never Arriving or Leaving
        if (!isSched) {
            // Leaving — terminal stop about to depart
            if (isTerminalStop && millisToDeparture != null && millisToDeparture in 0..60_000) {
                return "Leaving"
            }

            // Leaving — train at platform, departure imminent
            if (!isTerminalStop &&
                millisToArrival != null && millisToArrival <= 0 &&
                millisToDeparture != null && millisToDeparture in 0..60_000
            ) {
                return "Leaving"
            }

            // Arriving — train less than 1 minute away, has arrival data, not a terminal stop
            if (!isTerminalStop && millisToArrival != null && millisToArrival in 1..59_999) {
                return "Arriving"
            }
        }

        // Xmin — round up to avoid 0min
        val minutes = ((millisToDisplay + 59_999) / 60_000).toInt()
        return "${minutes}min"
    }
}

enum class Agency {
    BART, MUNI, CALTRAIN
}

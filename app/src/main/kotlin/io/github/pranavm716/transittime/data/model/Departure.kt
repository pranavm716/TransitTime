package io.github.pranavm716.transittime.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.pranavm716.transittime.model.WatchDeparture
import io.github.pranavm716.transittime.util.getDisplayTime

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
    val delaySeconds: Int? = null,
    val tripId: String?,
    val fetchedAt: Long
) {
    fun getDisplayTime(
        now: Long,
        displayMode: DisplayMode = DisplayMode.RELATIVE,
        hybridThresholdMinutes: Int = 60,
        departingWindowMillis: Long = 30_000
    ): String = getDisplayTime(arrivalTimestamp, departureTimestamp, isOriginStop, isScheduled, now, displayMode, hybridThresholdMinutes, departingWindowMillis)
}

fun Departure.toWatchDeparture(): WatchDeparture = WatchDeparture(
    stopId = stopId,
    routeName = routeName,
    headsign = headsign,
    agency = agency,
    arrivalTimestamp = arrivalTimestamp,
    departureTimestamp = departureTimestamp,
    isOriginStop = isOriginStop,
    isScheduled = isScheduled,
    delaySeconds = delaySeconds,
    fetchedAt = fetchedAt
)

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
)

enum class Agency {
    BART, MUNI, CALTRAIN
}

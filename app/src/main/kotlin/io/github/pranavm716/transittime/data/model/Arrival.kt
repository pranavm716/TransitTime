package io.github.pranavm716.transittime.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "arrivals")
data class Arrival(
    @PrimaryKey
    val id: String,
    val stopId: String,
    val routeName: String,
    val headsign: String,
    val agency: Agency,
    val arrivalTimestamp: Long,
    val fetchedAt: Long
)

enum class Agency {
    BART,
    MUNI
}
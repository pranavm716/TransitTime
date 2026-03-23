package io.github.pranavm716.transittime.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "widget_configs")
data class WidgetConfig(
    @PrimaryKey
    val widgetId: Int,
    val stopId: String,
    val stopName: String,
    val agency: Agency,
    val filteredHeadsigns: List<String>,
    val maxArrivals: Int,
    val lastFetchedAt: Long = 0L
) {
    init {
        require(maxArrivals in 1..5) { "maxArrivals must be between 1 and 5." }
    }
}
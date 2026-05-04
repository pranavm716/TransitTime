package io.github.pranavm716.transittime.model

import io.github.pranavm716.transittime.data.model.Agency

data class WatchDeparture(
    val stopId: String,
    val routeName: String,
    val headsign: String,
    val agency: Agency,
    val arrivalTimestamp: Long?,
    val departureTimestamp: Long?,
    val isOriginStop: Boolean,
    val isScheduled: Boolean,
    val delaySeconds: Int?,
    val fetchedAt: Long
)

package io.github.pranavm716.transittime.wear

import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.data.model.WidgetConfig
import io.github.pranavm716.transittime.model.TileRow
import io.github.pranavm716.transittime.model.TileSnapshot
import io.github.pranavm716.transittime.util.getDelayColor
import io.github.pranavm716.transittime.util.getDisplayTime
import io.github.pranavm716.transittime.util.groupDepartures

fun buildSnapshot(
    config: WidgetConfig,
    departures: List<Departure>,
    goModeActive: Boolean,
    goModeExpiresAt: Long
): TileSnapshot {
    val fetchedAt = config.lastFetchedAt.takeIf { it > 0L }
        ?: departures.maxOfOrNull { it.fetchedAt }
        ?: System.currentTimeMillis()

    val (groups, _) = groupDepartures(departures, config.filteredHeadsigns, config.maxDepartures, fetchedAt)
    val rows = groups.map { deps ->
        TileRow(
            routeName = deps.first().routeName,
            headsign = deps.first().headsign,
            displayTimes = deps.map { dep ->
                getDisplayTime(
                    arrivalTimestamp = dep.arrivalTimestamp,
                    departureTimestamp = dep.departureTimestamp,
                    isOriginStop = dep.isOriginStop,
                    isScheduled = dep.isScheduled,
                    now = fetchedAt,
                    displayMode = config.displayMode,
                    hybridThresholdMinutes = config.hybridThresholdMinutes
                )
            },
            delayColors = deps.map { dep ->
                getDelayColor(dep.delaySeconds, dep.isScheduled, config.delayColorMode)
            }
        )
    }

    return TileSnapshot(
        stopId = config.stopId,
        stopName = config.stopName,
        agency = config.agency,
        fetchedAt = fetchedAt,
        errorLabel = config.lastErrorLabel,
        goModeActive = goModeActive,
        goModeExpiresAt = goModeExpiresAt,
        rows = rows
    )
}

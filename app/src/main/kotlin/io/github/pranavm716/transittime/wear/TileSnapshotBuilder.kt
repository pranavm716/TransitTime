package io.github.pranavm716.transittime.wear

import io.github.pranavm716.transittime.data.model.Departure
import io.github.pranavm716.transittime.data.model.WidgetConfig
import io.github.pranavm716.transittime.model.TileRow
import io.github.pranavm716.transittime.model.TileSnapshot
import io.github.pranavm716.transittime.util.getDelayColor
import io.github.pranavm716.transittime.util.getDisplayTime

fun buildSnapshot(
    config: WidgetConfig,
    departures: List<Departure>,
    goModeActive: Boolean,
    goModeExpiresAt: Long
): TileSnapshot {
    val fetchedAt = config.lastFetchedAt.takeIf { it > 0L }
        ?: departures.maxOfOrNull { it.fetchedAt }
        ?: System.currentTimeMillis()

    val rows = departures
        .filter { dep ->
            (dep.departureTimestamp ?: dep.arrivalTimestamp ?: Long.MIN_VALUE) > fetchedAt &&
                (config.filteredHeadsigns.isEmpty() ||
                    "${dep.routeName}|${dep.headsign}" in config.filteredHeadsigns)
        }
        .groupBy { "${it.routeName}|${it.headsign}" }
        .entries
        .sortedBy { (_, deps) ->
            deps.minOf { d -> d.departureTimestamp ?: d.arrivalTimestamp ?: Long.MAX_VALUE }
        }
        .map { (_, deps) ->
            val dep = deps.minBy { d -> d.departureTimestamp ?: d.arrivalTimestamp ?: Long.MAX_VALUE }
            TileRow(
                routeName = dep.routeName,
                headsign = dep.headsign,
                displayTime = getDisplayTime(
                    arrivalTimestamp = dep.arrivalTimestamp,
                    departureTimestamp = dep.departureTimestamp,
                    isOriginStop = dep.isOriginStop,
                    isScheduled = dep.isScheduled,
                    now = fetchedAt,
                    displayMode = config.displayMode,
                    hybridThresholdMinutes = config.hybridThresholdMinutes
                ),
                delayColor = getDelayColor(dep.delaySeconds, dep.isScheduled, config.delayColorMode)
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

package io.github.pranavm716.transittime.util

import io.github.pranavm716.transittime.data.model.Departure

fun groupDepartures(
    departures: List<Departure>,
    filteredHeadsigns: List<String>,
    maxDepartures: Int,
    now: Long,
    maxRows: Int = Int.MAX_VALUE
): Pair<List<List<Departure>>, Int> {
    val allGroups = departures
        .filter { departure ->
            (departure.departureTimestamp ?: departure.arrivalTimestamp ?: Long.MIN_VALUE) > now &&
                (filteredHeadsigns.isEmpty() ||
                    "${departure.routeName}|${departure.headsign}" in filteredHeadsigns)
        }
        .groupBy { "${it.routeName}|${it.headsign}" }
        .entries
        .map { (_, deps) ->
            deps.sortedBy { it.arrivalTimestamp ?: it.departureTimestamp ?: Long.MAX_VALUE }
                .take(maxDepartures)
        }
        .sortedWith(
            compareBy(
                { it.first().arrivalTimestamp ?: it.first().departureTimestamp ?: Long.MAX_VALUE },
                { it.getOrNull(1)?.let { d -> d.arrivalTimestamp ?: d.departureTimestamp } ?: Long.MAX_VALUE },
                { it.getOrNull(2)?.let { d -> d.arrivalTimestamp ?: d.departureTimestamp } ?: Long.MAX_VALUE },
                { it.first().routeName }
            )
        )
    val overflow = (allGroups.size - maxRows).coerceAtLeast(0)
    return allGroups.take(maxRows) to overflow
}

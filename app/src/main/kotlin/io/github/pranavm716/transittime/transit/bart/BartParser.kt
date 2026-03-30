package io.github.pranavm716.transittime.transit.bart

import android.content.Context
import com.google.transit.realtime.GtfsRealtime
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.zip.ZipInputStream

object BartParser {

    private val stopNames = mutableMapOf<String, String>()         // parentStationId → stop_name
    private val platformToParent = mutableMapOf<String, String>()  // platformBaseId → parentStationId
    private val tripRouteIds = mutableMapOf<String, String>()      // tripId → routeId
    private val routeColors = mutableMapOf<String, String>()       // routeId → color name
    private val tripTerminals = mutableMapOf<String, String>()     // tripId → terminal parentStationId
    private var staticLoaded = false

    private val TERMINAL_CLEANUP = mapOf(
        "Berryessa / North San Jose" to "Berryessa",
        "Millbrae (Caltrain Transfer Platform)" to "Millbrae",
        "San Francisco International Airport" to "SF Airport",
    )

    fun loadStaticGtfs(context: Context) {
        if (staticLoaded) return
        val cached = getCachedGtfs(context)
        parseStaticZip(cached)
        staticLoaded = true
    }

    private fun getCachedGtfs(context: Context): InputStream {
        val cacheFile = java.io.File(context.cacheDir, "bart_gtfs.zip")
        val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        if (!cacheFile.exists() || ageMs > thirtyDaysMs) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://www.bart.gov/dev/schedules/google_transit.zip")
                .build()
            val bytes = client.newCall(request).execute().body.bytes()
            cacheFile.writeBytes(bytes)
        }
        return cacheFile.inputStream()
    }

    private fun parseStaticZip(input: InputStream) {
        val zip = ZipInputStream(input)
        val files = mutableMapOf<String, String>()
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name in listOf("stops.txt", "trips.txt", "routes.txt", "stop_times.txt")) {
                files[entry.name] = zip.readBytes().decodeToString()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        files["stops.txt"]?.let { parseStops(it) }
        files["trips.txt"]?.let { parseTrips(it) }
        files["routes.txt"]?.let { parseRoutes(it) }
        files["stop_times.txt"]?.let { parseStopTimes(it) }
    }

    private fun parseStops(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val idIdx = headers.indexOf("stop_id")
        val nameIdx = headers.indexOf("stop_name")
        val typeIdx = headers.indexOf("location_type")
        val parentIdx = headers.indexOf("parent_station")
        if (idIdx == -1 || nameIdx == -1 || typeIdx == -1 || parentIdx == -1) return
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size < maxOf(idIdx, nameIdx, typeIdx, parentIdx) + 1) continue
            val stopId = cols[idIdx].removeSurrounding("\"")
            val stopName = cols[nameIdx].removeSurrounding("\"")
            val locationType = cols[typeIdx].removeSurrounding("\"")
            val parentStation = cols[parentIdx].removeSurrounding("\"")
            when {
                locationType == "1" -> {
                    // Parent station — canonical ID used everywhere
                    stopNames[stopId] = stopName
                }
                locationType == "0" && parentStation.isNotEmpty() -> {
                    // Platform — map its base ID to the parent station
                    val baseId = if ("-" in stopId) stopId.substringBeforeLast("-") else stopId
                    platformToParent[baseId] = parentStation
                }
            }
        }
    }

    private fun parseTrips(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val tripIdx = headers.indexOf("trip_id")
        val routeIdx = headers.indexOf("route_id")
        if (tripIdx == -1 || routeIdx == -1) return
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size < maxOf(tripIdx, routeIdx) + 1) continue
            val tripId = cols[tripIdx].removeSurrounding("\"")
            tripRouteIds[tripId] = cols[routeIdx].removeSurrounding("\"").lowercase()
        }
    }

    private fun parseRoutes(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val idIdx = headers.indexOf("route_id")
        val colorIdx = headers.indexOf("route_color")
        if (idIdx == -1 || colorIdx == -1) return
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size < maxOf(idIdx, colorIdx) + 1) continue
            val routeId = cols[idIdx].removeSurrounding("\"").lowercase()
            val color = cols[colorIdx].removeSurrounding("\"").lowercase()
            routeColors[routeId] = hexToColorName(color)
        }
    }

    private fun parseStopTimes(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val tripIdx = headers.indexOf("trip_id")
        val stopIdx = headers.indexOf("stop_id")
        val seqIdx = headers.indexOf("stop_sequence")
        if (tripIdx == -1 || stopIdx == -1 || seqIdx == -1) return

        // Track max sequence per trip to find terminal
        val tripMaxSeq = mutableMapOf<String, Int>()
        val tripTerminalStop = mutableMapOf<String, String>()

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size < maxOf(tripIdx, stopIdx, seqIdx) + 1) continue
            val tripId = cols[tripIdx].removeSurrounding("\"")
            val stopId = cols[stopIdx].removeSurrounding("\"")
            val seq = cols[seqIdx].removeSurrounding("\"").toIntOrNull() ?: continue
            if (seq > (tripMaxSeq[tripId] ?: -1)) {
                tripMaxSeq[tripId] = seq
                tripTerminalStop[tripId] = stopId
            }
        }

        // Resolve terminal platform IDs to their parent station IDs
        for ((tripId, terminalStopId) in tripTerminalStop) {
            val baseId = if ("-" in terminalStopId) terminalStopId.substringBeforeLast("-") else terminalStopId
            tripTerminals[tripId] = platformToParent[baseId] ?: baseId
        }
    }

    private fun hexToColorName(hex: String): String = when (hex.trimStart('#')) {
        "ff0000", "cc0000" -> "Red"
        "0099cc", "1c9ac9", "0099d8" -> "Blue"
        "ffff33", "ffcc00", "f9a620", "ffff00" -> "Yellow"
        "339933", "00a550", "009b3a", "50b848" -> "Green"
        "ff9933", "f78f20", "ff8000", "faa61a" -> "Orange"
        else -> "Unknown"
    }

    private fun cleanTerminalName(raw: String): String =
        TERMINAL_CLEANUP[raw] ?: raw

    fun parseRtFeed(feedBytes: ByteArray, fetchedAt: Long): List<Departure> {
        val feed = FeedMessage.parseFrom(feedBytes)
        val departures = mutableListOf<Departure>()

        for (entity in feed.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tu = entity.tripUpdate

            // Skip cancelled trips
            if (tu.trip.scheduleRelationship ==
                GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
            ) continue

            val tripId = tu.trip.tripId
            val routeId = tripRouteIds[tripId] ?: continue
            val colorName = routeColors[routeId] ?: continue
            val routeName = "$colorName Line"
            val terminalStationId = tripTerminals[tripId] ?: continue
            val rawTerminalName = stopNames[terminalStationId] ?: continue
            val headsign = cleanTerminalName(rawTerminalName)

            for (stu in tu.stopTimeUpdateList) {
                if (stu.scheduleRelationship ==
                    GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED
                ) continue
                val baseId = if ("-" in stu.stopId) stu.stopId.substringBeforeLast("-") else stu.stopId
                val stationId = platformToParent[baseId] ?: continue

                val arrivalTimestamp: Long? = if (stu.hasArrival()) stu.arrival.time * 1000L else null
                val departureTimestamp: Long? = if (stu.hasDeparture()) stu.departure.time * 1000L else null
                if (arrivalTimestamp == null && departureTimestamp == null) continue

                departures.add(
                    Departure(
                        id = "${stationId}_${routeName}_${headsign}_${arrivalTimestamp ?: departureTimestamp}",
                        stopId = stationId,
                        routeName = routeName,
                        headsign = headsign,
                        agency = Agency.BART,
                        arrivalTimestamp = arrivalTimestamp,
                        departureTimestamp = departureTimestamp,
                        isTerminalStop = (stationId == terminalStationId),
                        isScheduled = false,
                        tripId = null,
                        fetchedAt = fetchedAt
                    )
                )
            }
        }

        // Deduplicate departures within 30 seconds of each other for same stop+route+headsign
        return departures
            .sortedBy { it.arrivalTimestamp ?: it.departureTimestamp ?: Long.MAX_VALUE }
            .fold(mutableListOf()) { acc, departure ->
                val duplicate = acc.any { existing ->
                    existing.stopId == departure.stopId &&
                            existing.routeName == departure.routeName &&
                            existing.headsign == departure.headsign &&
                            run {
                                val existingTs = existing.arrivalTimestamp ?: existing.departureTimestamp ?: Long.MIN_VALUE
                                val currentTs = departure.arrivalTimestamp ?: departure.departureTimestamp ?: Long.MIN_VALUE
                                kotlin.math.abs(existingTs - currentTs) < 30_000L
                            }
                }
                if (!duplicate) acc.add(departure)
                acc
            }
    }

    fun getStopNames(): Map<String, String> = stopNames.toMap()

    suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>> {
        // Use live RT feed to get currently running routes at this stop
        return try {
            val bytes = BartApiClient.api.getTripUpdates().bytes()
            val feed = FeedMessage.parseFrom(bytes)
            val result = mutableMapOf<String, MutableSet<String>>()

            for (entity in feed.entityList) {
                if (!entity.hasTripUpdate()) continue
                val tu = entity.tripUpdate
                val tripId = tu.trip.tripId
                val routeId = tripRouteIds[tripId] ?: continue
                val colorName = routeColors[routeId] ?: continue
                val routeName = "$colorName Line"
                val terminalStationId = tripTerminals[tripId] ?: continue
                val rawTerminalName = stopNames[terminalStationId] ?: continue
                val headsign = cleanTerminalName(rawTerminalName)

                for (stu in tu.stopTimeUpdateList) {
                    val baseId = if ("-" in stu.stopId) stu.stopId.substringBeforeLast("-") else stu.stopId
                    val stationId = platformToParent[baseId] ?: continue
                    if (stationId == stopId) {
                        result.getOrPut(routeName) { mutableSetOf() }.add(headsign)
                        break
                    }
                }
            }
            val routes = result.mapValues { it.value.toList().sorted() }
            routes
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

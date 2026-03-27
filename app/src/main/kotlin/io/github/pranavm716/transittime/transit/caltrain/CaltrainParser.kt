package io.github.pranavm716.transittime.transit.caltrain

import android.content.Context
import com.google.transit.realtime.GtfsRealtime
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import io.github.pranavm716.transittime.BuildConfig
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Arrival
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.zip.ZipInputStream

object CaltrainParser {

    // parentStationId → display name
    private val parentStations = mutableMapOf<String, String>()

    // platformId → parentStationId
    private val platformToParent = mutableMapOf<String, String>()

    // tripId → routeId (e.g. "Local Weekday", "Express")
    private val tripToRoute = mutableMapOf<String, String>()

    // tripId → headsign (e.g. "San Francisco", "San Jose Diridon")
    private val tripToHeadsign = mutableMapOf<String, String>()

    // parentStationId → routeName → Set<headsign>  (built from stop_times.txt)
    private val stationRoutes = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    private var staticLoaded = false

    private val SHUTTLE_STOPS = setOf("777402", "777403")
    private val EXCLUDED_STATIONS = setOf("stanford")

    fun loadStaticGtfs(context: Context) {
        if (staticLoaded) return
        val cached = getCachedGtfs(context)
        parseStaticZip(cached)
        staticLoaded = true
    }

    private fun getCachedGtfs(context: Context): InputStream {
        val cacheFile = java.io.File(context.cacheDir, "caltrain_gtfs.zip")
        val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        if (!cacheFile.exists() || ageMs > thirtyDaysMs) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.511.org/transit/datafeeds?api_key=${BuildConfig.MUNI_API_KEY}&operator_id=CT")
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
            if (entry.name in listOf("stops.txt", "trips.txt", "stop_times.txt")) {
                files[entry.name] = zip.readBytes().decodeToString()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        files["stops.txt"]?.let { parseStops(it) }
        files["trips.txt"]?.let { parseTrips(it) }
        files["stop_times.txt"]?.let { parseStopTimes(it) }
    }

    private fun parseStops(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val idIdx = headers.indexOf("stop_id")
        val nameIdx = headers.indexOf("stop_name")
        val parentIdx = headers.indexOf("parent_station")
        if (idIdx == -1 || nameIdx == -1 || parentIdx == -1) return

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size <= maxOf(idIdx, nameIdx, parentIdx)) continue
            val stopId = cols[idIdx]
            val stopName = cols[nameIdx]
            val parentStation = cols[parentIdx]

            if (stopId in SHUTTLE_STOPS) continue

            if (parentStation.isEmpty()) {
                // Parent station
                if (stopId in EXCLUDED_STATIONS) continue
                parentStations[stopId] = cleanStationName(stopName)
            } else {
                // Platform stop
                platformToParent[stopId] = parentStation
            }
        }
    }

    private fun cleanStationName(raw: String): String =
        raw.removeSuffix(" Caltrain Station")

    private fun parseTrips(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val tripIdx = headers.indexOf("trip_id")
        val routeIdx = headers.indexOf("route_id")
        val headsignIdx = headers.indexOf("trip_headsign")
        if (tripIdx == -1 || routeIdx == -1 || headsignIdx == -1) return

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size <= maxOf(tripIdx, routeIdx, headsignIdx)) continue
            val tripId = cols[tripIdx]
            val routeId = cols[routeIdx]
            val headsign = cols[headsignIdx]
            if (tripId.isEmpty() || routeId.isEmpty() || headsign.isEmpty()) continue
            tripToRoute[tripId] = routeId
            tripToHeadsign[tripId] = headsign
        }
    }

    private fun parseStopTimes(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val tripIdx = headers.indexOf("trip_id")
        val stopIdx = headers.indexOf("stop_id")
        if (tripIdx == -1 || stopIdx == -1) return

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size <= maxOf(tripIdx, stopIdx)) continue
            val tripId = cols[tripIdx]
            val platformId = cols[stopIdx]

            val parentId = platformToParent[platformId] ?: continue
            if (parentId in EXCLUDED_STATIONS) continue
            val routeName = tripToRoute[tripId] ?: continue
            val headsign = tripToHeadsign[tripId] ?: continue
            val stationDisplayName = parentStations[parentId] ?: continue
            if (headsign == stationDisplayName) continue // skip self-terminating headsigns

            stationRoutes
                .getOrPut(parentId) { mutableMapOf() }
                .getOrPut(routeName) { mutableSetOf() }
                .add(headsign)
        }
    }

    // Minimal CSV line parser that handles quoted fields
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val sb = StringBuilder()
        while (i < line.length) {
            if (line[i] == '"') {
                i++ // skip opening quote
                while (i < line.length && line[i] != '"') {
                    sb.append(line[i++])
                }
                i++ // skip closing quote
            } else if (line[i] == ',') {
                result.add(sb.toString())
                sb.clear()
                i++
            } else {
                sb.append(line[i++])
            }
        }
        result.add(sb.toString())
        return result
    }

    fun getStopNames(): Map<String, String> = parentStations.toMap()

    fun getRoutesForStation(stationId: String): Map<String, List<String>> =
        stationRoutes[stationId]?.mapValues { it.value.toList().sorted() } ?: emptyMap()

    fun parseRtFeed(feedBytes: ByteArray, fetchedAt: Long): List<Arrival> {
        val feed = FeedMessage.parseFrom(feedBytes)
        val arrivals = mutableListOf<Arrival>()

        for (entity in feed.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tu = entity.tripUpdate

            if (tu.trip.scheduleRelationship ==
                GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
            ) continue

            val tripId = tu.trip.tripId
            val routeName = tripToRoute[tripId] ?: continue
            val headsign = tripToHeadsign[tripId] ?: continue

            for (stu in tu.stopTimeUpdateList) {
                if (stu.scheduleRelationship ==
                    GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED
                ) continue

                val parentId = platformToParent[stu.stopId] ?: continue

                val arrivalTimestamp = when {
                    stu.hasArrival() -> stu.arrival.time * 1000L
                    stu.hasDeparture() -> stu.departure.time * 1000L
                    else -> continue
                }
                val departureTimestamp = if (stu.hasDeparture()) stu.departure.time * 1000L
                else arrivalTimestamp + 60_000L

                arrivals.add(
                    Arrival(
                        id = "${parentId}_${routeName}_${headsign}_${arrivalTimestamp}",
                        stopId = parentId,
                        routeName = routeName,
                        headsign = headsign,
                        agency = Agency.CALTRAIN,
                        arrivalTimestamp = arrivalTimestamp,
                        departureTimestamp = departureTimestamp,
                        fetchedAt = fetchedAt
                    )
                )
            }
        }

        // Deduplicate arrivals within 30 seconds for same stop+route+headsign
        return arrivals
            .sortedBy { it.arrivalTimestamp }
            .fold(mutableListOf()) { acc, arrival ->
                val duplicate = acc.any { existing ->
                    existing.stopId == arrival.stopId &&
                            existing.routeName == arrival.routeName &&
                            existing.headsign == arrival.headsign &&
                            kotlin.math.abs(existing.arrivalTimestamp - arrival.arrivalTimestamp) < 30_000L
                }
                if (!duplicate) acc.add(arrival)
                acc
            }
    }
}

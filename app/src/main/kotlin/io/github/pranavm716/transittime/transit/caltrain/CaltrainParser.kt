package io.github.pranavm716.transittime.transit.caltrain

import android.content.Context
import com.google.transit.realtime.GtfsRealtime
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import io.github.pranavm716.transittime.BuildConfig
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

data class ScheduledDeparture(
    val routeName: String,
    val headsign: String,
    val departureTimestamp: Long,
    val tripId: String
)

fun mergeWithTimetable(
    rtDepartures: List<Departure>,
    scheduledDepartures: List<ScheduledDeparture>,
    maxArrivals: Int,
    now: Long,
    stopId: String,
    fetchedAt: Long,
    tripTerminals: Map<String, String>,
    tripOrigins: Map<String, String> = emptyMap(),
    allRtTripIds: Set<String> = emptySet()
): List<Departure> {
    val result = rtDepartures.toMutableList()

    val rtByKey = rtDepartures.groupBy { "${it.routeName}|${it.headsign}" }

    val schedByKey = scheduledDepartures
        .filter { it.departureTimestamp > now }
        .groupBy { "${it.routeName}|${it.headsign}" }

    for ((key, scheduled) in schedByKey) {
        // Only count future RT departures — past ones will be filtered out by the widget,
        // so counting them would under-fill the scheduled slots and show fewer than maxArrivals.
        val rtCount = (rtByKey[key] ?: emptyList())
            .count { (it.departureTimestamp ?: it.arrivalTimestamp ?: 0L) > now }
        val needed = maxArrivals - rtCount
        if (needed <= 0) continue

        var filled = 0
        for (dep in scheduled.sortedBy { it.departureTimestamp }) {
            if (filled >= needed) break
            if (dep.tripId in allRtTripIds) continue
            if (dep.departureTimestamp <= now) continue

            // Robust filtering: skip if this stop is the terminal for the trip
            if (stopId == tripTerminals[dep.tripId]) continue

            val isOrigin = (stopId == tripOrigins[dep.tripId])

            result.add(
                Departure(
                    id = "${stopId}_${dep.routeName}_${dep.headsign}_${dep.departureTimestamp}",
                    stopId = stopId,
                    routeName = dep.routeName,
                    headsign = dep.headsign,
                    agency = Agency.CALTRAIN,
                    arrivalTimestamp = null,
                    departureTimestamp = dep.departureTimestamp,
                    isOriginStop = isOrigin,
                    isScheduled = true,
                    delaySeconds = null,
                    tripId = dep.tripId,
                    fetchedAt = fetchedAt
                )
            )
            filled++
        }
    }

    // Discard scheduled entries that fall before the earliest RT entry for their group.
    // Guards against ghosts from trains that departed early and dropped off the RT feed.
    return result
        .groupBy { "${it.routeName}|${it.headsign}" }
        .flatMap { (_, groupDepartures) ->
            val firstRtTimestamp = groupDepartures
                .filter { !it.isScheduled }
                .mapNotNull { it.departureTimestamp }
                .minOrNull()

            if (firstRtTimestamp == null) {
                groupDepartures
            } else {
                groupDepartures.filter { departure ->
                    !departure.isScheduled ||
                    (departure.departureTimestamp ?: Long.MAX_VALUE) >= firstRtTimestamp
                }
            }
        }
        .sortedBy { it.departureTimestamp ?: it.arrivalTimestamp ?: Long.MAX_VALUE }
}

object CaltrainParser {

    // parentStationId → display name
    private val parentStations = mutableMapOf<String, String>()

    // platformId → parentStationId
    private val platformToParent = mutableMapOf<String, String>()

    // tripId → routeId (e.g. "Local Weekday", "Express")
    private val tripToRoute = mutableMapOf<String, String>()

    // tripId → headsign (e.g. "San Francisco", "San Jose Diridon")
    private val tripToHeadsign = mutableMapOf<String, String>()

    // tripId → serviceId
    private val tripServiceId = mutableMapOf<String, String>()

    // tripId → terminal parentStationId
    private val tripTerminals = mutableMapOf<String, String>()

    // tripId → origin parentStationId
    private val tripOrigins = mutableMapOf<String, String>()

    // parentStationId → routeName → Set<headsign>  (built from stop_times.txt)
    private val stationRoutes = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    // parentStationId → list of (tripId, departureSeconds)
    private val stationDepartures = mutableMapOf<String, MutableList<Pair<String, Int>>>()

    // calendar.txt rows as column-name maps
    private val calendarRows = mutableListOf<Map<String, String>>()

    // calendar_dates.txt rows as column-name maps
    private val calendarDateRows = mutableListOf<Map<String, String>>()

    private var staticLoaded = false

    private val SHUTTLE_STOPS = setOf("777402", "777403")
    private val EXCLUDED_STATIONS = setOf("stanford")
    private val PT = ZoneId.of("America/Los_Angeles")

    fun loadStaticGtfs(context: Context) {
        if (staticLoaded) return
        val cached = getCachedGtfs(context)
        parseStaticZip(cached)
        if (parentStations.isEmpty()) return
        staticLoaded = true
    }

    private fun isValidZip(file: java.io.File): Boolean {
        if (file.length() < 4) return false
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        return header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
    }

    private fun getCachedGtfs(context: Context): InputStream {
        val cacheFile = java.io.File(context.cacheDir, "caltrain_gtfs.zip")
        val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        if (!cacheFile.exists() || ageMs > thirtyDaysMs || !isValidZip(cacheFile)) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.511.org/transit/datafeeds?api_key=${BuildConfig.TRANSIT511_API_KEY}&operator_id=CT")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw java.io.IOException("511 API returned HTTP ${response.code}")
            val bytes = response.body.bytes()
            cacheFile.writeBytes(bytes)
        }
        return cacheFile.inputStream()
    }

    private fun parseStaticZip(input: InputStream) {
        val zip = ZipInputStream(input)
        val files = mutableMapOf<String, String>()
        var entry = zip.nextEntry
        while (entry != null) {
            val fileName = entry.name.substringAfterLast('/')
            if (fileName in listOf(
                    "stops.txt", "trips.txt", "stop_times.txt",
                    "calendar.txt", "calendar_dates.txt"
                )
            ) {
                files[fileName] = zip.readBytes().decodeToString()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        files["stops.txt"]?.let { parseStops(it) }
        files["trips.txt"]?.let { parseTrips(it) }
        files["stop_times.txt"]?.let { parseStopTimes(it) }
        files["calendar.txt"]?.let { parseCalendar(it) }
        files["calendar_dates.txt"]?.let { parseCalendarDates(it) }
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
                if (stopId in EXCLUDED_STATIONS) continue
                parentStations[stopId] = cleanStationName(stopName)
            } else {
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
        val serviceIdx = headers.indexOf("service_id")
        if (tripIdx == -1 || routeIdx == -1 || headsignIdx == -1 || serviceIdx == -1) return

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size <= maxOf(tripIdx, routeIdx, headsignIdx, serviceIdx)) continue
            val tripId = cols[tripIdx]
            val routeId = cols[routeIdx]
            val headsign = cols[headsignIdx]
            val serviceId = cols[serviceIdx]
            if (tripId.isEmpty() || routeId.isEmpty() || headsign.isEmpty() || serviceId.isEmpty()) continue
            tripToRoute[tripId] = routeId
            tripToHeadsign[tripId] = headsign
            tripServiceId[tripId] = serviceId
        }
    }

    private fun parseStopTimes(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val tripIdx = headers.indexOf("trip_id")
        val stopIdx = headers.indexOf("stop_id")
        val depIdx = headers.indexOf("departure_time")
        val seqIdx = headers.indexOf("stop_sequence")
        if (tripIdx == -1 || stopIdx == -1 || depIdx == -1) return

        // Pass 1: find terminal/origin platform IDs for each trip
        val tripMaxSeq = mutableMapOf<String, Int>()
        val tripTerminalPlatform = mutableMapOf<String, String>()
        val tripMinSeq = mutableMapOf<String, Int>()
        val tripOriginPlatform = mutableMapOf<String, String>()

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size <= maxOf(tripIdx, stopIdx, depIdx)) continue
            val tripId = cols[tripIdx]
            val platformId = cols[stopIdx]
            
            if (seqIdx != -1 && cols.size > seqIdx) {
                val seq = cols[seqIdx].toIntOrNull()
                if (seq != null) {
                    if (seq > (tripMaxSeq[tripId] ?: -1)) {
                        tripMaxSeq[tripId] = seq
                        tripTerminalPlatform[tripId] = platformId
                    }
                    if (seq < (tripMinSeq[tripId] ?: Int.MAX_VALUE)) {
                        tripMinSeq[tripId] = seq
                        tripOriginPlatform[tripId] = platformId
                    }
                }
            }
        }

        // Resolve terminal/origin platform IDs to their parent station IDs
        for ((tripId, platformId) in tripTerminalPlatform) {
            tripTerminals[tripId] = platformToParent[platformId] ?: platformId
        }
        for ((tripId, platformId) in tripOriginPlatform) {
            tripOrigins[tripId] = platformToParent[platformId] ?: platformId
        }

        // Pass 2: build stationRoutes and stationDepartures, skipping terminal stops
        val recorded = mutableSetOf<String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size <= maxOf(tripIdx, stopIdx, depIdx)) continue
            val tripId = cols[tripIdx]
            val platformId = cols[stopIdx]
            val departureTimeStr = cols[depIdx]

            val parentId = platformToParent[platformId] ?: continue
            if (parentId in EXCLUDED_STATIONS) continue
            
            // SKIP if this stop is the terminal for the trip
            if (parentId == tripTerminals[tripId]) continue

            val routeName = tripToRoute[tripId] ?: continue
            val headsign = tripToHeadsign[tripId] ?: continue

            // Build the route filter map (deduplicated by route+headsign naturally via Set)
            stationRoutes
                .getOrPut(parentId) { mutableMapOf() }
                .getOrPut(routeName) { mutableSetOf() }
                .add(headsign)

            // Build the scheduled departure list (one entry per parentId+tripId)
            val key = "$parentId|$tripId"
            if (recorded.add(key)) {
                val departureSeconds = parseStopTimeSeconds(departureTimeStr)
                if (departureSeconds >= 0) {
                    stationDepartures.getOrPut(parentId) { mutableListOf() }
                        .add(Pair(tripId, departureSeconds))
                }
            }
        }
    }

    private fun parseCalendar(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size < headers.size) continue
            calendarRows.add(headers.zip(cols).toMap())
        }
    }

    private fun parseCalendarDates(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size < headers.size) continue
            calendarDateRows.add(headers.zip(cols).toMap())
        }
    }

    private fun parseStopTimeSeconds(time: String): Int {
        return try {
            val parts = time.split(":")
            parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
        } catch (_: Exception) {
            -1
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

    fun getTripTerminals(): Map<String, String> = tripTerminals.toMap()

    fun getTripOrigins(): Map<String, String> = tripOrigins.toMap()

    fun getRoutesForStation(stationId: String): Map<String, List<String>> =
        stationRoutes[stationId]?.mapValues { it.value.toList().sorted() } ?: emptyMap()

    fun getActiveServices(): Set<String> {
        val today = LocalDate.now(PT)
        val todayStr = today.format(DateTimeFormatter.BASIC_ISO_DATE)
        val dayColumn = today.dayOfWeek.name.lowercase()

        val activeServices = mutableSetOf<String>()

        for (row in calendarRows) {
            val startStr = row["start_date"] ?: continue
            val endStr = row["end_date"] ?: continue
            val serviceId = row["service_id"] ?: continue
            val start = LocalDate.parse(startStr, DateTimeFormatter.BASIC_ISO_DATE)
            val end = LocalDate.parse(endStr, DateTimeFormatter.BASIC_ISO_DATE)
            if (!today.isBefore(start) && !today.isAfter(end) && row[dayColumn] == "1") {
                activeServices.add(serviceId)
            }
        }

        for (row in calendarDateRows) {
            if (row["date"] == todayStr) {
                val serviceId = row["service_id"] ?: continue
                when (row["exception_type"]) {
                    "1" -> activeServices.add(serviceId)
                    "2" -> activeServices.remove(serviceId)
                }
            }
        }

        return activeServices
    }

    fun getScheduledDepartures(
        stationId: String,
        activeServices: Set<String>
    ): List<ScheduledDeparture> {
        // Deduplicate by tripId — the GTFS can list multiple platform entries per trip at the
        // same parent station, which would otherwise cause the same trip to appear twice.
        val departures = stationDepartures[stationId]?.distinctBy { it.first } ?: return emptyList()

        val midnightToday = LocalDate.now(PT).atStartOfDay(PT).toEpochSecond()

        val result = mutableListOf<ScheduledDeparture>()
        for ((tripId, departureSeconds) in departures) {
            val serviceId = tripServiceId[tripId] ?: continue
            if (serviceId !in activeServices) continue
            val routeName = tripToRoute[tripId] ?: continue
            val headsign = tripToHeadsign[tripId] ?: continue

            // Robust filtering: skip if this stop is the terminal for the trip
            if (stationId == tripTerminals[tripId]) continue

            val departureTimestamp = (midnightToday + departureSeconds) * 1000L
            result.add(ScheduledDeparture(routeName, headsign, departureTimestamp, tripId))
        }

        return result
    }

    fun parseRtFeed(feedBytes: ByteArray, fetchedAt: Long): List<Departure> {
        val feed = FeedMessage.parseFrom(feedBytes)
        val departures = mutableListOf<Departure>()

        for (entity in feed.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tu = entity.tripUpdate

            if (tu.trip.scheduleRelationship ==
                GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
            ) continue

            val tripId = tu.trip.tripId
            val routeName = tripToRoute[tripId] ?: continue
            val headsign = tripToHeadsign[tripId] ?: continue
            val terminalStationId = tripTerminals[tripId]
            val originStationId = tripOrigins[tripId]

            for (stu in tu.stopTimeUpdateList) {
                if (stu.scheduleRelationship ==
                    GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED
                ) continue

                val parentId = platformToParent[stu.stopId] ?: continue

                // Robust filtering: skip if this stop is the terminal for this trip
                if (parentId == terminalStationId) continue

                val arrivalTimestamp: Long? = when {
                    stu.hasArrival() -> stu.arrival.time * 1000L
                    stu.hasDeparture() -> stu.departure.time * 1000L
                    else -> null
                }
                val departureTimestamp: Long? = if (stu.hasDeparture()) stu.departure.time * 1000L else null
                if (arrivalTimestamp == null && departureTimestamp == null) continue

                val isOrigin = (parentId == originStationId)
                
                departures.add(
                    Departure(
                        id = "${parentId}_${routeName}_${headsign}_${arrivalTimestamp ?: departureTimestamp}",
                        stopId = parentId,
                        routeName = routeName,
                        headsign = headsign,
                        agency = Agency.CALTRAIN,
                        arrivalTimestamp = arrivalTimestamp,
                        departureTimestamp = departureTimestamp,
                        isOriginStop = isOrigin,
                        isScheduled = false,
                        delaySeconds = stu.departure.delay,
                        tripId = tripId,
                        fetchedAt = fetchedAt
                    )
                )
            }
        }

        // Deduplicate departures within 30 seconds for same stop+route+headsign
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
}

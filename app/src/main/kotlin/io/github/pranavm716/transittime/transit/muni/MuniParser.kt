package io.github.pranavm716.transittime.transit.muni

import android.content.Context
import io.github.pranavm716.transittime.BuildConfig
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Departure
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

object MuniParser {

    val METRO_STATIONS = mapOf(
        "metro:Castro" to listOf("15728", "16991"),
        "metro:Church" to listOf("15726", "16998"),
        "metro:CivicCenter" to listOf("15727", "16997"),
        "metro:Embarcadero" to listOf("16992", "17217"),
        "metro:ForestHill" to listOf("15730", "16993"),
        "metro:Montgomery" to listOf("15731", "16994"),
        "metro:Powell" to listOf("15417", "16995"),
        "metro:VanNess" to listOf("15419", "16996"),
    )

    private val METRO_DISPLAY_NAMES = mapOf(
        "metro:Castro" to "Castro",
        "metro:Church" to "Church",
        "metro:CivicCenter" to "Civic Center / UN Plaza",
        "metro:Embarcadero" to "Embarcadero",
        "metro:ForestHill" to "Forest Hill",
        "metro:Montgomery" to "Montgomery Street",
        "metro:Powell" to "Powell Street",
        "metro:VanNess" to "Van Ness",
    )

    // logicalStopId → display name
    // For single stops: logicalStopId = raw stop ID e.g. "13238"
    // For merged stops: logicalStopId = "merged:13238,13239"
    private val busStopDisplayNames = mutableMapOf<String, String>()

    // logicalStopId → list of raw stop IDs to query
    private val busStopIds = mutableMapOf<String, List<String>>()

    private var staticLoaded = false

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun loadStaticGtfs(context: Context) {
        if (staticLoaded) return
        try {
            val cached = getCachedGtfs(context)
            parseStaticZip(cached)
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Muni stop data failed to load: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        if (busStopDisplayNames.isEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Muni data loaded but empty — check 511 API key", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        staticLoaded = true
    }

    private fun isValidZip(file: java.io.File): Boolean {
        if (file.length() < 4) return false
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        return header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
    }

    private fun getCachedGtfs(context: Context): InputStream {
        val cacheFile = java.io.File(context.cacheDir, "muni_gtfs.zip")
        val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

        if (!cacheFile.exists() || ageMs > thirtyDaysMs || !isValidZip(cacheFile)) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.511.org/transit/datafeeds?api_key=${BuildConfig.MUNI_API_KEY}&operator_id=SF")
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
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name.substringAfterLast('/') == "stops.txt") {
                parseStops(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).readText())
                break
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    private fun parseStops(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val idIdx = headers.indexOf("stop_id")
        val nameIdx = headers.indexOf("stop_name")
        if (idIdx == -1 || nameIdx == -1) return

        val metroStopIds = METRO_STATIONS.values.flatten().toSet()

        // First pass: group raw stop IDs by exact name
        val nameToIds = mutableMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size < maxOf(idIdx, nameIdx) + 1) continue
            val stopId = cols[idIdx].removeSurrounding("\"")
            val stopName = cols[nameIdx].removeSurrounding("\"")
            if (stopId in metroStopIds) continue
            nameToIds.getOrPut(stopName) { mutableListOf() }.add(stopId)
        }

        // Second pass: build logical stop entries
        for ((name, ids) in nameToIds) {
            val logicalId = if (ids.size == 1) {
                ids.first()
            } else {
                "merged:${ids.sorted().joinToString(",")}"
            }
            busStopDisplayNames[logicalId] = name
            busStopIds[logicalId] = ids
        }
    }

    fun getStopNames(): Map<String, String> {
        return busStopDisplayNames + METRO_DISPLAY_NAMES
    }

    fun getStopIdsForStop(stopId: String): List<String> {
        // Metro station
        METRO_STATIONS[stopId]?.let { return it }
        // Merged bus stop
        busStopIds[stopId]?.let { return it }
        // Single bus stop (fallback)
        return listOf(stopId)
    }

    suspend fun fetchAndParseStop(
        stopId: String,
        fetchedAt: Long
    ): List<Departure> {
        val platformIds = getStopIdsForStop(stopId)
        val allDepartures = mutableListOf<Departure>()

        for (platformId in platformIds) {
            try {
                val responseBody = MuniApiClient.api.getStopMonitoring(
                    apiKey = BuildConfig.MUNI_API_KEY,
                    stopCode = platformId
                )
                allDepartures.addAll(parseStopMonitoring(responseBody.string(), stopId, fetchedAt))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Deduplicate departures within 30 seconds for same route+headsign
        return allDepartures
            .sortedBy { it.arrivalTimestamp ?: Long.MAX_VALUE }
            .fold(mutableListOf()) { acc, departure ->
                val duplicate = acc.any { existing ->
                    existing.routeName == departure.routeName &&
                            existing.headsign == departure.headsign &&
                            run {
                                val existingTs = existing.arrivalTimestamp ?: Long.MIN_VALUE
                                val currentTs = departure.arrivalTimestamp ?: Long.MIN_VALUE
                                kotlin.math.abs(existingTs - currentTs) < 30_000L
                            }
                }
                if (!duplicate) acc.add(departure)
                acc
            }
    }

    fun parseStopMonitoring(json: String, stopId: String, fetchedAt: Long): List<Departure> {
        val departures = mutableListOf<Departure>()
        try {
            val root = JSONObject(json)
            val delivery = root
                .getJSONObject("ServiceDelivery")
                .getJSONObject("StopMonitoringDelivery")
            val visits = delivery.optJSONArray("MonitoredStopVisit") ?: return emptyList()

            val platformIds = getStopIdsForStop(stopId).toSet()

            for (i in 0 until visits.length()) {
                val visit = visits.getJSONObject(i)
                val journey = visit.getJSONObject("MonitoredVehicleJourney")
                val call = journey.getJSONObject("MonitoredCall")

                val destinationRef = journey.optString("DestinationRef")
                
                // Robust filtering: check if the vehicle's destination matches any platform of our logical stop
                if (platformIds.contains(destinationRef)) continue

                val expectedArrivalStr = call.optString("ExpectedArrivalTime", "")
                val expectedDepartureStr = call.optString("ExpectedDepartureTime", "")

                val arrivalTimestamp = if (expectedArrivalStr.isNotEmpty() && expectedArrivalStr != "null") {
                    try { isoFormat.parse(expectedArrivalStr)?.time } catch (_: Exception) { null }
                } else null

                val departureTimestamp = if (expectedDepartureStr.isNotEmpty() && expectedDepartureStr != "null") {
                    try { isoFormat.parse(expectedDepartureStr)?.time } catch (_: Exception) { null }
                } else null

                if (arrivalTimestamp == null && departureTimestamp == null) continue

                val lineRef = journey.optString("LineRef", "")
                    .takeIf { it.isNotEmpty() } ?: continue
                val headsign = call.optString("DestinationDisplay", "")
                    .takeIf { it.isNotEmpty() } ?: continue

                // Robust origin detection: check if the vehicle's origin matches any platform of our logical stop,
                // or if it lacks an expected arrival time.
                val originRef = journey.optString("OriginRef")
                val isOrigin = platformIds.contains(originRef) || 
                        expectedArrivalStr.isEmpty() || expectedArrivalStr == "null"

                departures.add(
                    Departure(
                        id = "${stopId}_${lineRef}_${arrivalTimestamp ?: departureTimestamp}",
                        stopId = stopId,
                        routeName = lineRef,
                        headsign = headsign,
                        agency = Agency.MUNI,
                        arrivalTimestamp = arrivalTimestamp,
                        departureTimestamp = departureTimestamp,
                        isOriginStop = isOrigin,
                        isScheduled = false,
                        tripId = null,
                        fetchedAt = fetchedAt
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return departures
    }

    fun parseRoutesFromResponse(json: String, platformIds: Set<String>): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        try {
            val root = JSONObject(json)
            val delivery = root
                .getJSONObject("ServiceDelivery")
                .getJSONObject("StopMonitoringDelivery")
            val visits = delivery.optJSONArray("MonitoredStopVisit") ?: return emptyMap()

            for (i in 0 until visits.length()) {
                val journey = visits.getJSONObject(i)
                    .getJSONObject("MonitoredVehicleJourney")
                
                // Robust filtering: check if the vehicle's destination matches any platform of our logical stop
                val destinationRef = journey.optString("DestinationRef")
                if (platformIds.contains(destinationRef)) continue
                
                val lineRef = journey.optString("LineRef", "")
                    .takeIf { it.isNotEmpty() } ?: continue
                val headsign = journey.getJSONObject("MonitoredCall").optString("DestinationDisplay", "")
                    .takeIf { it.isNotEmpty() } ?: continue
                result.getOrPut(lineRef) { mutableSetOf() }.add(headsign)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.mapValues { it.value.toList().sorted() }
    }

    suspend fun fetchRoutesForStop(stopId: String): Map<String, List<String>> {
        val platformIds = getStopIdsForStop(stopId)
        val platformIdSet = platformIds.toSet()
        val combined = mutableMapOf<String, MutableSet<String>>()

        for (platformId in platformIds) {
            try {
                val responseBody = MuniApiClient.api.getStopMonitoring(
                    apiKey = BuildConfig.MUNI_API_KEY,
                    stopCode = platformId
                )
                val routes = parseRoutesFromResponse(responseBody.string(), platformIdSet)
                routes.forEach { (line, headsigns) ->
                    combined.getOrPut(line) { mutableSetOf() }.addAll(headsigns)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return combined.mapValues { it.value.toList().sorted() }
    }
}

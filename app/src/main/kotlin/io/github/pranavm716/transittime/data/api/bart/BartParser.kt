package io.github.pranavm716.transittime.data.api.bart

import android.content.Context
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.Arrival
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.zip.ZipInputStream

object BartParser {

    // In-memory lookup tables populated once from static GTFS
    private val stopNames = mutableMapOf<String, String>()     // "M16" -> "Embarcadero"
    private val tripHeadsigns = mutableMapOf<String, String>() // "1849326" -> "Millbrae..."
    private val routeColors = mutableMapOf<String, String>() // routeId → "Red", "Blue" etc
    private val tripRouteIds = mutableMapOf<String, String>() // tripId → routeId

    private var staticLoaded = false

    // --- Static GTFS loading ---

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
            if (entry.name in listOf("stops.txt", "trips.txt", "routes.txt")) {
                files[entry.name] = zip.readBytes().decodeToString()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }

        files["stops.txt"]?.let { parseStops(it) }
        files["trips.txt"]?.let { parseTrips(it) }
        files["routes.txt"]?.let { parseRoutes(it) }
    }

    private fun parseStops(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }  // strip quotes
        val idIdx = headers.indexOf("stop_id")
        val nameIdx = headers.indexOf("stop_name")
        if (idIdx == -1 || nameIdx == -1) return
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size < maxOf(idIdx, nameIdx)) continue
            val stopId = cols[idIdx].removeSurrounding("\"")
            if ("-" in stopId && "_" !in stopId) {
                val baseId = stopId.substringBeforeLast("-")
                stopNames[baseId] = cols[nameIdx].removeSurrounding("\"")
            }
        }
    }

    private fun parseTrips(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
            .map { it.trim().removeSurrounding("\"") }
        val tripIdx = headers.indexOf("trip_id")
        val headsignIdx = headers.indexOf("trip_headsign")
        val routeIdx = headers.indexOf("route_id")
        if (tripIdx == -1 || headsignIdx == -1) return
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size < maxOf(tripIdx, headsignIdx) + 1) continue
            val tripId = cols[tripIdx].removeSurrounding("\"")
            tripHeadsigns[tripId] = cols[headsignIdx].removeSurrounding("\"")
            if (routeIdx != -1 && cols.size > routeIdx) {
                tripRouteIds[tripId] = cols[routeIdx].removeSurrounding("\"").lowercase()
            }
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

    private fun hexToColorName(hex: String): String = when (hex.trimStart('#')) {
        "ff0000", "cc0000" -> "Red"
        "0099cc", "1c9ac9", "0000cc" -> "Blue"
        "ffff33", "ffcc00", "f9a620" -> "Yellow"
        "339933", "00a550", "009b3a" -> "Green"
        "ff9933", "f78f20", "ff8000" -> "Orange"
        else -> "Unknown"
    }

    private fun normalizeHeadsign(raw: String): String? = when {
        raw.contains("Richmond", ignoreCase = true) -> "Richmond"
        raw.contains("Millbrae", ignoreCase = true) -> "Millbrae"
        raw.contains("Dublin", ignoreCase = true) -> "Dublin/Pleasanton"
        raw.contains("Daly City", ignoreCase = true) -> "Daly City"
        raw.contains("San Francisco International", ignoreCase = true) ||
                raw.contains("SFO", ignoreCase = true) -> "SFO International Airport"

        raw.contains("Antioch", ignoreCase = true) -> "Antioch"
        raw.contains("Berryessa", ignoreCase = true) -> "Berryessa"
        else -> null
    }

    fun parseRtFeed(feedBytes: ByteArray, fetchedAt: Long): List<Arrival> {
        val feed = FeedMessage.parseFrom(feedBytes)
        val arrivals = mutableListOf<Arrival>()

        for (entity in feed.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tu = entity.tripUpdate
            val tripId = tu.trip.tripId
            val rawHeadsign = tripHeadsigns[tripId] ?: continue
            val headsign = normalizeHeadsign(rawHeadsign) ?: continue
            val routeId = tripRouteIds[tripId] ?: ""
            val colorName = routeColors[routeId] ?: "Unknown"
            val routeName = "$colorName Line"

            for (stu in tu.stopTimeUpdateList) {
                if (!stu.hasArrival()) continue
                val baseId = stu.stopId.substringBeforeLast("-")
                stopNames[baseId] ?: continue
                val arrivalTimestamp = stu.arrival.time * 1000L

                arrivals.add(
                    Arrival(
                        id = "${baseId}_${headsign}_${arrivalTimestamp}",
                        stopId = baseId,
                        routeName = routeName,
                        headsign = headsign,
                        agency = Agency.BART,
                        arrivalTimestamp = arrivalTimestamp,
                        fetchedAt = fetchedAt
                    )
                )
            }
        }

        android.util.Log.d(
            "BartParser",
            "Total arrivals parsed: ${arrivals.size}, stopNames loaded: ${stopNames.size}, tripHeadsigns loaded: ${tripHeadsigns.size}"
        )
        return arrivals
    }

    fun getStopNames(): Map<String, String> = stopNames.toMap()
}
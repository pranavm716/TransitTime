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
        if (!cacheFile.exists()) {
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
        var entry = zip.nextEntry
        while (entry != null) {
            when (entry.name) {
                "stops.txt" -> parseStops(zip.readBytes().decodeToString())
                "trips.txt" -> parseTrips(zip.readBytes().decodeToString())
            }
            entry = zip.nextEntry
        }
    }

    private fun parseStops(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
        val idIdx = headers.indexOf("stop_id")
        val nameIdx = headers.indexOf("stop_name")
        if (idIdx == -1 || nameIdx == -1) return  // guard against bad data
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size <= maxOf(idIdx, nameIdx)) continue  // guard short rows
            val stopId = cols[idIdx]
            if ("-" in stopId && "_" !in stopId) {
                val baseId = stopId.substringBeforeLast("-")
                stopNames[baseId] = cols[nameIdx]
            }
        }
    }

    private fun parseTrips(csv: String) {
        val lines = csv.lines()
        val headers = lines.first().trimStart('\uFEFF').split(",")
        val tripIdx = headers.indexOf("trip_id")
        val headsignIdx = headers.indexOf("trip_headsign")
        if (tripIdx == -1 || headsignIdx == -1) return
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size <= maxOf(tripIdx, headsignIdx)) continue
            tripHeadsigns[cols[tripIdx]] = cols[headsignIdx]
        }
    }

    // --- RT feed parsing ---

    fun parseRtFeed(feedBytes: ByteArray, fetchedAt: Long): List<Arrival> {
        val feed = FeedMessage.parseFrom(feedBytes)
        val arrivals = mutableListOf<Arrival>()

        for (entity in feed.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tu = entity.tripUpdate
            val tripId = tu.trip.tripId
            val headsign = tripHeadsigns[tripId] ?: continue

            for (stu in tu.stopTimeUpdateList) {
                if (!stu.hasArrival()) continue
                val baseId = stu.stopId.substringBeforeLast("-")
                val stopName = stopNames[baseId] ?: continue
                val arrivalTimestamp = stu.arrival.time * 1000L  // seconds -> milliseconds

                arrivals.add(
                    Arrival(
                        id = "${baseId}_${headsign}_${arrivalTimestamp}",
                        stopId = baseId,
                        routeName = headsign,   // BART has no distinct route name
                        headsign = headsign,
                        agency = Agency.BART,
                        arrivalTimestamp = arrivalTimestamp,
                        fetchedAt = fetchedAt
                    )
                )
            }
        }
        return arrivals
    }
}
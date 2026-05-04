package io.github.pranavm716.transittime.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.pranavm716.transittime.model.WatchDeparture
import io.github.pranavm716.transittime.model.WatchStopConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object WearDataLayerReader {

    private const val TAG = "TransitWear"
    private val gson = Gson()

    fun deserializeStopConfigs(json: String): List<WatchStopConfig> {
        val type = object : TypeToken<List<WatchStopConfig>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun deserializeDepartures(json: String): List<WatchDeparture> {
        val type = object : TypeToken<List<WatchDeparture>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun readStopConfigs(context: Context, cache: WearLocalCache): List<WatchStopConfig>? = withContext(Dispatchers.IO) {
        val uri = "wear://*/stop_configs"
        Log.d(TAG, "readStopConfigs: querying Data Layer uri=$uri")
        try {
            val dataItems = Wearable.getDataClient(context)
                .getDataItems(Uri.parse(uri))
                .await()
            try {
                Log.d(TAG, "readStopConfigs: got ${dataItems.count} item(s)")
                val dataItem = dataItems.firstOrNull()
                if (dataItem == null) {
                    Log.d(TAG, "readStopConfigs: no item found — returning empty list")
                    return@withContext emptyList()
                }
                val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                val pushedAt = dataMap.getLong("pushedAt")
                if (pushedAt == cache.getConfigsPushedAt()) {
                    val cached = cache.getStopConfigs()
                    Log.d(TAG, "readStopConfigs: pushedAt match, returning ${cached.size} cached configs")
                    return@withContext cached
                }
                val json = dataMap.getString("configs")
                if (json == null) {
                    Log.d(TAG, "readStopConfigs: 'configs' key missing — returning empty list")
                    return@withContext emptyList()
                }
                val result = deserializeStopConfigs(json)
                Log.d(TAG, "readStopConfigs: deserialized ${result.size} configs: ${result.map { it.stopName }}")
                cache.saveStopConfigs(result, pushedAt)
                result
            } finally {
                dataItems.release()
            }
        } catch (e: Exception) {
            Log.d(TAG, "readStopConfigs: query failed with exception", e)
            null
        }
    }

    suspend fun readDepartures(context: Context, stopId: String, cache: WearLocalCache): Pair<List<WatchDeparture>, Long> =
        withContext(Dispatchers.IO) {
            try {
                val dataItems = Wearable.getDataClient(context)
                    .getDataItems(Uri.parse("wear://*/departures/$stopId"))
                    .await()
                try {
                    val item = dataItems.firstOrNull() ?: return@withContext Pair(emptyList(), 0L)
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val fetchedAt = dataMap.getLong("fetchedAt")
                    if (fetchedAt == cache.getFetchedAt(stopId)) {
                        return@withContext Pair(cache.getDepartures(stopId), fetchedAt)
                    }
                    val json = dataMap.getString("departures") ?: return@withContext Pair(emptyList(), 0L)
                    val departures = deserializeDepartures(json)
                    cache.saveDepartures(stopId, departures, fetchedAt)
                    Pair(departures, fetchedAt)
                } finally {
                    dataItems.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Pair(emptyList(), 0L)
            }
        }

    suspend fun readGoModeExpiresAt(context: Context): Long = withContext(Dispatchers.IO) {
        try {
            val dataItems = Wearable.getDataClient(context)
                .getDataItems(Uri.parse("wear://*/go_mode"))
                .await()
            try {
                dataItems.firstOrNull()
                    ?.let { DataMapItem.fromDataItem(it).dataMap.getLong("expiresAt") }
                    ?: 0L
            } finally {
                dataItems.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }
}

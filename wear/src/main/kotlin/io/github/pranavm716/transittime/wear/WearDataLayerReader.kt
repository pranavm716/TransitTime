package io.github.pranavm716.transittime.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.pranavm716.transittime.model.TileSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object WearDataLayerReader {

    private const val TAG = "TransitWear"
    private val gson = Gson()

    suspend fun readSnapshot(context: Context, stopId: String, cache: WearLocalCache): TileSnapshot? =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "readSnapshot: checking cache for stopId=$stopId")
            try {
                val dataItems = Wearable.getDataClient(context)
                    .getDataItems(Uri.parse("wear://*/tile_snapshot/$stopId"))
                    .await()
                try {
                    val item = dataItems.firstOrNull()
                    if (item == null) {
                        Log.d(TAG, "readSnapshot: no Data Layer item for stopId=$stopId, falling back to cache")
                        return@withContext cache.getSnapshot(stopId)
                    }
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val pushedAt = dataMap.getLong("pushedAt")
                    val cachedPushedAt = cache.getPushedAt(stopId)
                    Log.d(TAG, "readSnapshot: stopId=$stopId, pushedAt=$pushedAt, cachedPushedAt=$cachedPushedAt")
                    if (pushedAt == cachedPushedAt) {
                        return@withContext cache.getSnapshot(stopId)
                    }
                    val json = dataMap.getString("snapshot") ?: run {
                        Log.d(TAG, "readSnapshot: 'snapshot' key missing for stopId=$stopId")
                        return@withContext null
                    }
                    val snapshot = gson.fromJson(json, TileSnapshot::class.java)
                    Log.d(TAG, "readSnapshot: fresh snapshot for stopId=$stopId, rows=${snapshot?.rows?.size}")
                    if (snapshot != null) cache.saveSnapshot(snapshot, pushedAt)
                    snapshot
                } finally {
                    dataItems.release()
                }
            } catch (e: Exception) {
                Log.d(TAG, "readSnapshot: query failed for stopId=$stopId, falling back to cache", e)
                cache.getSnapshot(stopId)
            }
        }

    suspend fun readStopIds(context: Context, cache: WearLocalCache): List<String> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "readStopIds: checking cache")
            try {
                val dataItems = Wearable.getDataClient(context)
                    .getDataItems(Uri.parse("wear://*/tile_snapshot_index"))
                    .await()
                try {
                    val item = dataItems.firstOrNull()
                    if (item == null) {
                        Log.d(TAG, "readStopIds: no Data Layer item, falling back to cache")
                        return@withContext cache.getStopIds()
                    }
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val pushedAt = dataMap.getLong("pushedAt")
                    val cachedPushedAt = cache.getStopIdsPushedAt()
                    Log.d(TAG, "readStopIds: pushedAt=$pushedAt, cachedPushedAt=$cachedPushedAt")
                    if (pushedAt == cachedPushedAt) {
                        return@withContext cache.getStopIds()
                    }
                    val json = dataMap.getString("stopIds") ?: return@withContext emptyList()
                    val type = object : TypeToken<List<String>>() {}.type
                    val stopIds: List<String> = gson.fromJson(json, type) ?: emptyList()
                    Log.d(TAG, "readStopIds: fresh stopIds=$stopIds")
                    cache.saveStopIds(stopIds, pushedAt)
                    stopIds
                } finally {
                    dataItems.release()
                }
            } catch (e: Exception) {
                Log.d(TAG, "readStopIds: query failed, falling back to cache", e)
                cache.getStopIds()
            }
        }
}

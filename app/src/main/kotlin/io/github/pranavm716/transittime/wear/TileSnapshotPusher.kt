package io.github.pranavm716.transittime.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import io.github.pranavm716.transittime.model.TileSnapshot
import kotlinx.coroutines.tasks.await

class TileSnapshotPusher(context: Context) {

    private val dataClient = Wearable.getDataClient(context)
    private val gson = Gson()

    suspend fun pushSnapshot(snapshot: TileSnapshot, isFetchResult: Boolean = false) {
        val request = PutDataMapRequest.create("/tile_snapshot/${snapshot.stopId}").apply {
            dataMap.putString("snapshot", gson.toJson(snapshot))
            dataMap.putLong("fetchedAt", snapshot.fetchedAt)
            dataMap.putLong("pushedAt", System.currentTimeMillis())
            dataMap.putBoolean("isFetchResult", isFetchResult)
            dataMap.putBoolean("isRefreshing", snapshot.isRefreshing ?: false)
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
    }

    suspend fun deleteSnapshot(stopId: String) {
        dataClient.deleteDataItems(Uri.parse("wear://*/tile_snapshot/$stopId")).await()
    }

    // Signals the watch to navigate to this stop on next tile render (consumed once).
    suspend fun pushFocusStop(stopId: String) {
        val request = PutDataMapRequest.create("/focus_stop").apply {
            dataMap.putString("stopId", stopId)
            dataMap.putLong("pushedAt", System.currentTimeMillis())
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
    }

    // Pushes an ordered list of stopIds so the watch knows how to navigate stops.
    suspend fun pushStopIndex(orderedStopIds: List<String>) {
        val request = PutDataMapRequest.create("/tile_snapshot_index").apply {
            dataMap.putString("stopIds", gson.toJson(orderedStopIds))
            dataMap.putLong("pushedAt", System.currentTimeMillis())
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
    }
}

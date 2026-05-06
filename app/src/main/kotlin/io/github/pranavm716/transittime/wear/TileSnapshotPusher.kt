package io.github.pranavm716.transittime.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import io.github.pranavm716.transittime.model.TileSnapshot
import kotlinx.coroutines.tasks.await

class TileSnapshotPusher(context: Context) {

    private val dataClient = Wearable.getDataClient(context)
    private val gson = Gson()

    companion object {
        private const val TAG = "TransitWear"
    }

    suspend fun pushSnapshot(snapshot: TileSnapshot, isFetchResult: Boolean = false) {
        Log.d(TAG, "pushSnapshot: stopId=${snapshot.stopId}, rows=${snapshot.rows.size}, isFetchResult=$isFetchResult")
        val request = PutDataMapRequest.create("/tile_snapshot/${snapshot.stopId}").apply {
            dataMap.putString("snapshot", gson.toJson(snapshot))
            dataMap.putLong("fetchedAt", snapshot.fetchedAt)
            dataMap.putLong("pushedAt", System.currentTimeMillis())
            dataMap.putBoolean("isFetchResult", isFetchResult)
            dataMap.putBoolean("isRefreshing", snapshot.isRefreshing ?: false)
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
        Log.d(TAG, "pushSnapshot: succeeded for stopId=${snapshot.stopId}")
    }

    suspend fun deleteSnapshot(stopId: String) {
        Log.d(TAG, "deleteSnapshot: stopId=$stopId")
        dataClient.deleteDataItems(Uri.parse("wear://*/tile_snapshot/$stopId")).await()
        Log.d(TAG, "deleteSnapshot: succeeded for stopId=$stopId")
    }

    // Pushes an ordered list of stopIds so the watch knows how to navigate stops.
    suspend fun pushStopIndex(orderedStopIds: List<String>) {
        Log.d(TAG, "pushStopIndex: stopIds=$orderedStopIds")
        val request = PutDataMapRequest.create("/tile_snapshot_index").apply {
            dataMap.putString("stopIds", gson.toJson(orderedStopIds))
            dataMap.putLong("pushedAt", System.currentTimeMillis())
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
        Log.d(TAG, "pushStopIndex: succeeded")
    }
}

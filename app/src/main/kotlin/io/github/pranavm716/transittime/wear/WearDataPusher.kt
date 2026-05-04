package io.github.pranavm716.transittime.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import io.github.pranavm716.transittime.model.WatchDeparture
import io.github.pranavm716.transittime.model.WatchStopConfig
import kotlinx.coroutines.tasks.await

class WearDataPusher(context: Context) {

    private val dataClient = Wearable.getDataClient(context)
    private val gson = Gson()

    companion object {
        private const val TAG = "TransitWear"
    }

    suspend fun pushDepartures(stopId: String, departures: List<WatchDeparture>, fetchedAt: Long) {
        Log.d(TAG, "pushDepartures: pushing ${departures.size} departures for stopId=$stopId")
        val request = PutDataMapRequest.create("/departures/$stopId").apply {
            dataMap.putString("departures", gson.toJson(departures))
            dataMap.putLong("fetchedAt", fetchedAt)
        }
        try {
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
            Log.d(TAG, "pushDepartures: putDataItem succeeded for stopId=$stopId")
        } catch (e: Exception) {
            Log.d(TAG, "pushDepartures: putDataItem failed for stopId=$stopId: $e")
            throw e
        }
    }

    suspend fun pushStopConfigs(configs: List<WatchStopConfig>, pushedAt: Long) {
        Log.d(TAG, "pushStopConfigs: pushing ${configs.size} configs: ${configs.map { it.stopName }}")
        val request = PutDataMapRequest.create("/stop_configs").apply {
            dataMap.putString("configs", gson.toJson(configs))
            dataMap.putLong("pushedAt", pushedAt)
        }
        try {
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
            Log.d(TAG, "pushStopConfigs: putDataItem succeeded")
        } catch (e: Exception) {
            Log.d(TAG, "pushStopConfigs: putDataItem failed: $e")
            throw e
        }
    }

    suspend fun pushGoModeState(expiresAt: Long) {
        val request = PutDataMapRequest.create("/go_mode").apply {
            dataMap.putLong("expiresAt", expiresAt)
        }
        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
    }
}

package io.github.pranavm716.transittime.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import io.github.pranavm716.transittime.model.WatchDeparture
import io.github.pranavm716.transittime.model.WatchStopConfig
import kotlinx.coroutines.tasks.await

class WearDataPusher(context: Context) {

    private val dataClient = Wearable.getDataClient(context)
    private val gson = Gson()

    suspend fun pushDepartures(stopId: String, departures: List<WatchDeparture>, fetchedAt: Long) {
        val request = PutDataMapRequest.create("/departures/$stopId").apply {
            dataMap.putString("departures", gson.toJson(departures))
            dataMap.putLong("fetchedAt", fetchedAt)
        }
        dataClient.putDataItem(request.asPutDataRequest()).await()
    }

    suspend fun pushStopConfigs(configs: List<WatchStopConfig>) {
        val request = PutDataMapRequest.create("/stop_configs").apply {
            dataMap.putString("configs", gson.toJson(configs))
        }
        dataClient.putDataItem(request.asPutDataRequest()).await()
    }

    suspend fun pushGoModeState(expiresAt: Long) {
        val request = PutDataMapRequest.create("/go_mode").apply {
            dataMap.putLong("expiresAt", expiresAt)
        }
        dataClient.putDataItem(request.asPutDataRequest()).await()
    }
}

package io.github.pranavm716.transittime.wear

import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class PhoneDataListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "TransitWear"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged: received ${dataEvents.count} event(s)")
        val cache = WearLocalCache(this)
        try {
            for (event in dataEvents) {
                val item = event.dataItem
                val path = item.uri.path
                Log.d(TAG, "onDataChanged: event type=${event.type} path=$path uri=${item.uri}")
                if (path == null) continue
                val dataMap = DataMapItem.fromDataItem(item).dataMap

                when {
                    path.startsWith("/departures/") -> {
                        val stopId = path.removePrefix("/departures/")
                        val json = dataMap.getString("departures") ?: continue
                        val fetchedAt = dataMap.getLong("fetchedAt")
                        val departures = WearDataLayerReader.deserializeDepartures(json)
                        if (departures.isEmpty()) continue
                        cache.saveDepartures(stopId, departures, fetchedAt)
                        TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                    }
                    path == "/stop_configs" -> {
                        val json = dataMap.getString("configs") ?: continue
                        val configs = WearDataLayerReader.deserializeStopConfigs(json)
                        if (configs.isEmpty()) continue
                        cache.saveStopConfigs(configs)
                        TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                    }
                    path == "/go_mode" -> {
                        val expiresAt = dataMap.getLong("expiresAt")
                        cache.saveGoModeExpiresAt(expiresAt)
                        TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                    }
                    else -> Log.d(TAG, "onDataChanged: unhandled path=$path")
                }
            }
        } finally {
            dataEvents.release()
        }
    }
}

package io.github.pranavm716.transittime.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.pranavm716.transittime.model.WatchDeparture
import io.github.pranavm716.transittime.model.WatchStopConfig

class PhoneDataListenerService : WearableListenerService() {

    private val gson = Gson()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val cache = WearLocalCache(this)
        try {
            for (event in dataEvents) {
                val item = event.dataItem
                val path = item.uri.path ?: continue
                val dataMap = DataMapItem.fromDataItem(item).dataMap

                when {
                    path.startsWith("/departures/") -> {
                        val stopId = path.removePrefix("/departures/")
                        val json = dataMap.getString("departures") ?: continue
                        val fetchedAt = dataMap.getLong("fetchedAt")
                        val type = object : TypeToken<List<WatchDeparture>>() {}.type
                        val departures: List<WatchDeparture> = gson.fromJson(json, type) ?: continue
                        cache.saveDepartures(stopId, departures, fetchedAt)
                        TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                    }
                    path == "/stop_configs" -> {
                        val json = dataMap.getString("configs") ?: continue
                        val type = object : TypeToken<List<WatchStopConfig>>() {}.type
                        val configs: List<WatchStopConfig> = gson.fromJson(json, type) ?: continue
                        cache.saveStopConfigs(configs)
                        TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                    }
                    path == "/go_mode" -> {
                        val expiresAt = dataMap.getLong("expiresAt")
                        cache.saveGoModeExpiresAt(expiresAt)
                        TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                    }
                }
            }
        } finally {
            dataEvents.release()
        }
    }
}

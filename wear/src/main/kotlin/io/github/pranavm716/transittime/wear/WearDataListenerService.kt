package io.github.pranavm716.transittime.wear

import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class WearDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("WearDataListener", "onDataChanged: ${dataEvents.count} event(s)")
        val cache = WearLocalCache(this)
        var shouldRefresh = false
        for (event in dataEvents) {
            val path = event.dataItem.uri.path ?: continue
            Log.d("WearDataListener", "type=${event.type} path=$path")
            if (event.type == DataEvent.TYPE_CHANGED) {
                when {
                    path.startsWith("/tile_snapshot/") -> {
                        val stopId = path.substringAfter("/tile_snapshot/")
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val isFetchResult = dataMap.getBoolean("isFetchResult", false)
                        if (isFetchResult) cache.setRefreshing(stopId, false)
                        cache.setLocalGoModeOverride(null)
                        shouldRefresh = true
                    }
                    path == "/tile_snapshot_index" -> {
                        shouldRefresh = true
                    }
                }
            }
        }
        dataEvents.release()
        if (shouldRefresh) {
            Log.d("WearDataListener", "new snapshot data — requesting tile update")
            TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
        }
    }
}

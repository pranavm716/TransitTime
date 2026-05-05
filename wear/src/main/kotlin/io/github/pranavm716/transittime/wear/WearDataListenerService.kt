package io.github.pranavm716.transittime.wear

import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

class WearDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("WearDataListener", "onDataChanged: ${dataEvents.count} event(s)")
        var shouldRefresh = false
        for (event in dataEvents) {
            val path = event.dataItem.uri.path ?: continue
            Log.d("WearDataListener", "type=${event.type} path=$path")
            if (event.type == DataEvent.TYPE_CHANGED &&
                (path.startsWith("/tile_snapshot/") || path == "/tile_snapshot_index")
            ) {
                shouldRefresh = true
            }
        }
        dataEvents.release()
        if (shouldRefresh) {
            Log.d("WearDataListener", "new snapshot data — requesting tile update")
            TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
        }
    }
}

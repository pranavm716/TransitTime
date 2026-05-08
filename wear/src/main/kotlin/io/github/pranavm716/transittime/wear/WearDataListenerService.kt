package io.github.pranavm716.transittime.wear

import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_ANIM_DURATION_MS = 480L

class WearDataListenerService : WearableListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        (scope.coroutineContext[Job] as? Job)?.cancel()
        super.onDestroy()
    }

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

                        val snapshotJson = dataMap.getString("snapshot")
                        if (snapshotJson != null) {
                            try {
                                val snapshot = com.google.gson.Gson().fromJson(
                                    snapshotJson,
                                    io.github.pranavm716.transittime.model.TileSnapshot::class.java
                                )
                                val pushedAt = dataMap.getLong("pushedAt")
                                cache.saveSnapshot(snapshot, pushedAt)
                                val localOverride = cache.getLocalGoModeOverride()
                                if (localOverride != null) {
                                    Log.d(
                                        "LiveNotif",
                                        "Snapshot received for ${snapshot.stopId}, clearing local override ($localOverride) in favor of phone state (${snapshot.goModeActive})"
                                    )
                                    cache.setLocalGoModeOverride(null)
                                }
                            } catch (e: Exception) {
                                Log.e("WearDataListener", "Failed to parse/save snapshot", e)
                            }
                        }

                        if (isFetchResult) {
                            val startTime = cache.getRefreshingStartTime(stopId)
                            scope.launch {
                                val elapsed = System.currentTimeMillis() - startTime
                                if (startTime > 0 && elapsed < MIN_ANIM_DURATION_MS) {
                                    delay(MIN_ANIM_DURATION_MS - elapsed)
                                }
                                cache.setRefreshing(stopId, false)
                                TileService.getUpdater(this@WearDataListenerService)
                                    .requestUpdate(TransitTileService::class.java)
                                GoModeNotificationService.update(this@WearDataListenerService)
                            }
                        } else if (dataMap.getBoolean("isRefreshing", false)) {
                            cache.setRefreshing(stopId, true)
                        }
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
            GoModeNotificationService.update(this)
            TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
        }
    }
}

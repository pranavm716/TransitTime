package io.github.pranavm716.transittime.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransitTileService : TileService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = CallbackToFutureAdapter.getFuture { completer ->
        serviceScope.launch {
            try {
                val tile = tileRequestInternal(requestParams)
                completer.set(tile)
            } catch (e: Exception) {
                completer.setException(e)
            }
        }
        "onTileRequest"
    }

    private suspend fun tileRequestInternal(
        requestParams: RequestBuilders.TileRequest
    ): TileBuilders.Tile = withContext(Dispatchers.IO) {
        val cache = WearLocalCache(this@TransitTileService)

        var configs = cache.getStopConfigs()
        if (configs.isEmpty()) {
            configs = WearDataLayerReader.readStopConfigs(this@TransitTileService)
            if (configs.isNotEmpty()) cache.saveStopConfigs(configs)
        }

        val firstConfig = configs.firstOrNull()

        if (firstConfig != null && cache.getDepartures(firstConfig.stopId).isEmpty()) {
            val (departures, fetchedAt) = WearDataLayerReader.readDepartures(this@TransitTileService, firstConfig.stopId)
            if (departures.isNotEmpty()) cache.saveDepartures(firstConfig.stopId, departures, fetchedAt)
        }

        if (cache.getGoModeExpiresAt() == 0L) {
            val expiresAt = WearDataLayerReader.readGoModeExpiresAt(this@TransitTileService)
            if (expiresAt != 0L) cache.saveGoModeExpiresAt(expiresAt)
        }

        val stopName = firstConfig?.stopName ?: "No stops"

        TileBuilders.Tile.Builder()
            .setResourcesVersion("0")
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(
                                        LayoutElementBuilders.Text.Builder()
                                            .setText(
                                                TypeBuilders.StringProp.Builder(stopName).build()
                                            )
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    @Deprecated("Deprecated in Java")
    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<androidx.wear.tiles.ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(androidx.wear.tiles.ResourceBuilders.Resources.Builder().setVersion("0").build())
        "onResourcesRequest"
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

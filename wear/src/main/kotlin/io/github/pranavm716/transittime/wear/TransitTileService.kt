package io.github.pranavm716.transittime.wear

import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
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

        val configs = WearDataLayerReader.readStopConfigs(this@TransitTileService, cache)
            ?: cache.getStopConfigs()

        val state = requestParams.currentState
        val currentIndex = state.lastClickableId.toIntOrNull() ?: 0
        val config = configs.getOrNull(currentIndex)

        Log.d("TransitTile", "tileRequestInternal: configs=${configs.map { it.stopName }}, index=$currentIndex")

        val (departures, fetchedAt) = if (config != null) {
            WearDataLayerReader.readDepartures(this@TransitTileService, config.stopId, cache)
        } else Pair(emptyList(), 0L)

        if (cache.getGoModeExpiresAt() == 0L) {
            val expiresAt = WearDataLayerReader.readGoModeExpiresAt(this@TransitTileService)
            if (expiresAt != 0L) cache.saveGoModeExpiresAt(expiresAt)
        }
        val stopName = config?.stopName ?: "No stops"
        val nextIndex = if (configs.isNotEmpty()) (currentIndex + 1) % configs.size else 0

        TransitTileRenderer.renderTile(
            context = this@TransitTileService,
            deviceConfiguration = requestParams.deviceConfiguration,
            stopName = stopName,
            currentIndex = currentIndex,
            nextIndex = nextIndex,
            totalConfigs = configs.size,
            departures = departures,
            fetchedAt = fetchedAt
        )
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

package io.github.pranavm716.transittime.wear

import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.model.TileSnapshot
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
        val stopIds = WearDataLayerReader.readStopIds(this@TransitTileService, cache)
        val currentIndex = (requestParams.currentState.lastClickableId.toIntOrNull() ?: 0)
            .coerceIn(0, (stopIds.size - 1).coerceAtLeast(0))
        val nextIndex = if (stopIds.size > 1) (currentIndex + 1) % stopIds.size else 0
        val stopId = stopIds.getOrNull(currentIndex)

        Log.d("TransitTile", "tileRequestInternal: stopIds=$stopIds, index=$currentIndex, stopId=$stopId")

        val snapshot: TileSnapshot? = if (stopId != null) {
            WearDataLayerReader.readSnapshot(this@TransitTileService, stopId, cache)
        } else null

        val effectiveSnapshot = snapshot ?: TileSnapshot(
            stopId = stopId ?: "",
            stopName = if (stopIds.isEmpty()) "No stops configured" else "Loading…",
            agency = Agency.BART,
            fetchedAt = 0L,
            errorLabel = null,
            goModeActive = false,
            goModeExpiresAt = 0L,
            rows = emptyList()
        )

        TransitTileRenderer.renderTile(
            context = this@TransitTileService,
            deviceConfiguration = requestParams.deviceConfiguration,
            snapshot = effectiveSnapshot,
            currentIndex = currentIndex,
            nextIndex = nextIndex,
            totalStops = stopIds.size
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<androidx.wear.tiles.ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                androidx.wear.tiles.ResourceBuilders.Resources.Builder().setVersion("0").build()
            )
            "onResourcesRequest"
        }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

package io.github.pranavm716.transittime.wear

import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.model.TileSnapshot
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
        val lastClickableId = requestParams.currentState.lastClickableId
        val tappedIndex = lastClickableId.toIntOrNull()
        val savedIndex = cache.getCurrentIndex().coerceIn(0, (stopIds.size - 1).coerceAtLeast(0))
        val currentIndex = if (tappedIndex != null) {
            tappedIndex.coerceIn(0, (stopIds.size - 1).coerceAtLeast(0))
        } else {
            // Resolve by stop ID so the watch stays on the same stop when the list changes.
            val savedStopId = cache.getCurrentStopId()
            val resolvedByStopId = savedStopId?.let { stopIds.indexOf(it) }?.takeIf { it >= 0 }
            resolvedByStopId ?: savedIndex
        }
        // prevIndex is the old saved index — used to animate the arc from its previous position.
        // On data refresh (no tap), prevIndex == currentIndex so no animation plays.
        val prevIndex = if (tappedIndex != null) savedIndex else currentIndex
        cache.saveCurrentIndex(currentIndex)
        val stopId = stopIds.getOrNull(currentIndex)
        stopId?.let { cache.saveCurrentStopId(it) }

        if (lastClickableId == "refresh" && stopId != null) {
            performRefresh(stopId, cache)
        } else if (lastClickableId == "go_mode" && stopId != null) {
            performGoModeToggle(stopId, cache)
        }

        val nextIndex = if (stopIds.size > 1) (currentIndex + 1) % stopIds.size else 0

        Log.d(
            "TransitTile",
            "tileRequestInternal: stopIds=$stopIds, index=$currentIndex, stopId=$stopId"
        )

        val localIsRefreshing = stopId?.let { cache.getRefreshingStartTime(it) > 0 } ?: false

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

        val isRefreshing = localIsRefreshing || (effectiveSnapshot.isRefreshing == true)
        val effectiveGoModeActive = cache.getLocalGoModeOverride() ?: effectiveSnapshot.goModeActive

        TransitTileRenderer.renderTile(
            context = this@TransitTileService,
            deviceConfiguration = requestParams.deviceConfiguration,
            snapshot = effectiveSnapshot,
            currentIndex = currentIndex,
            prevIndex = prevIndex,
            nextIndex = nextIndex,
            totalStops = stopIds.size,
            isRefreshing = isRefreshing,
            goModeActive = effectiveGoModeActive
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<androidx.wear.tiles.ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                androidx.wear.tiles.ResourceBuilders.Resources.Builder()
                    .setVersion(TransitTileRenderer.RESOURCES_VERSION)
                    .addIdToImageMapping("ic_bart", androidRes(R.drawable.ic_bart))
                    .addIdToImageMapping("ic_muni", androidRes(R.drawable.ic_muni))
                    .addIdToImageMapping("ic_caltrain", androidRes(R.drawable.ic_caltrain))
                    .addIdToImageMapping("ic_refresh", androidRes(R.drawable.ic_refresh))
                    .addIdToImageMapping("ic_go_mode_dot", androidRes(R.drawable.ic_go_mode_dot))
                    .build()
            )
            "onResourcesRequest"
        }

    private fun androidRes(resId: Int): androidx.wear.tiles.ResourceBuilders.ImageResource =
        androidx.wear.tiles.ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                androidx.wear.tiles.ResourceBuilders.AndroidImageResourceByResId.Builder()
                    .setResourceId(resId)
                    .build()
            )
            .build()

    private suspend fun performRefresh(stopId: String, cache: WearLocalCache) {
        val localOverride = cache.getLocalGoModeOverride()
        val snapshot = cache.getSnapshot(stopId)
        val effectiveGoModeActive = localOverride ?: (snapshot?.goModeActive ?: false)
        if (!effectiveGoModeActive) {
            cache.setRefreshing(stopId, true)
        }
        sendMessageToPhone("/action/refresh", null)
    }

    private suspend fun performGoModeToggle(stopId: String, cache: WearLocalCache) {
        val localOverride = cache.getLocalGoModeOverride()
        val snapshot = cache.getSnapshot(stopId)
        val effectiveGoModeActive = localOverride ?: (snapshot?.goModeActive ?: false)
        if (!effectiveGoModeActive) {
            cache.setLocalGoModeOverride(true)
            cache.setRefreshing(stopId, true)
        } else {
            cache.setLocalGoModeOverride(false)
            cache.setRefreshing(stopId, false)
        }
        GoModeNotificationService.update(this@TransitTileService)
        sendMessageToPhone("/action/go_mode_toggle", stopId.toByteArray(Charsets.UTF_8))
    }

    private suspend fun sendMessageToPhone(path: String, payload: ByteArray?) {
        try {
            val nodes = Wearable.getNodeClient(this@TransitTileService).connectedNodes.await()
            val phone = nodes.firstOrNull()
            if (phone != null) {
                Wearable.getMessageClient(this@TransitTileService)
                    .sendMessage(phone.id, path, payload)
                    .await()
            }
        } catch (e: Exception) {
            Log.e("TransitTile", "Error sending message to phone", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

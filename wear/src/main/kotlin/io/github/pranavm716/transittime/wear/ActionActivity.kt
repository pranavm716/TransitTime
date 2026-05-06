package io.github.pranavm716.transittime.wear

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ActionActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val EXTRA_ACTION = "action"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: run { finish(); return }
        Log.d("ActionActivity", "onCreate: action=$action")

        scope.launch {
            try {
                val cache = WearLocalCache(this@ActionActivity)
                val currentStopId = cache.getCurrentStopId()
                if (action == "/action/refresh" && currentStopId != null) {
                    val localOverride = cache.getLocalGoModeOverride()
                    val snapshot = cache.getSnapshot(currentStopId)
                    val effectiveGoModeActive = localOverride ?: (snapshot?.goModeActive ?: false)
                    if (!effectiveGoModeActive) {
                        cache.setRefreshing(currentStopId, true)
                    }
                } else if (action == "/action/go_mode_toggle" && currentStopId != null) {
                    val localOverride = cache.getLocalGoModeOverride()
                    val snapshot = cache.getSnapshot(currentStopId)
                    val effectiveGoModeActive = localOverride ?: (snapshot?.goModeActive ?: false)
                    if (!effectiveGoModeActive) {
                        // Activating: show green dot pulsing immediately
                        cache.setLocalGoModeOverride(true)
                        cache.setRefreshing(currentStopId, true)
                    } else {
                        // Deactivating: show refresh icon immediately, no animation
                        cache.setLocalGoModeOverride(false)
                        cache.setRefreshing(currentStopId, false)
                    }
                }

                val nodes = Wearable.getNodeClient(this@ActionActivity).connectedNodes.await()
                Log.d("ActionActivity", "connectedNodes=${nodes.map { it.displayName }}")
                val phone = nodes.firstOrNull()
                if (phone != null) {
                    Log.d("ActionActivity", "sending message path=$action to phone=${phone.displayName}")
                    val payload = if (action == "/action/go_mode_toggle") {
                        if (cache.getLocalGoModeOverride() == true) {
                            android.util.Log.d("LiveNotif", "go_mode_toggle activating for stopId=$currentStopId")
                        }
                        currentStopId?.toByteArray(Charsets.UTF_8)
                    } else null
                    Wearable.getMessageClient(this@ActionActivity)
                        .sendMessage(phone.id, action, payload)
                        .await()
                    Log.d("ActionActivity", "message sent successfully")
                } else {
                    Log.w("ActionActivity", "no connected phone node found — message not sent")
                }
            } catch (e: Exception) {
                Log.e("ActionActivity", "error sending message", e)
            } finally {
                withContext(Dispatchers.Main) {
                    Log.d("ActionActivity", "requesting tile update")
                    TileService.getUpdater(this@ActionActivity)
                        .requestUpdate(TransitTileService::class.java)
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

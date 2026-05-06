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
                    cache.setRefreshing(currentStopId, true)
                }

                val nodes = Wearable.getNodeClient(this@ActionActivity).connectedNodes.await()
                Log.d("ActionActivity", "connectedNodes=${nodes.map { it.displayName }}")
                val phone = nodes.firstOrNull()
                if (phone != null) {
                    Log.d("ActionActivity", "sending message path=$action to phone=${phone.displayName}")
                    Wearable.getMessageClient(this@ActionActivity)
                        .sendMessage(phone.id, action, null)
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

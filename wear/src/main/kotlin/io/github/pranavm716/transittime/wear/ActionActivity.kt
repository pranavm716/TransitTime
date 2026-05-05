package io.github.pranavm716.transittime.wear

import android.app.Activity
import android.os.Bundle
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

        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@ActionActivity)
                    .connectedNodes
                    .await()
                val phone = nodes.firstOrNull()
                if (phone != null) {
                    Wearable.getMessageClient(this@ActionActivity)
                        .sendMessage(phone.id, action, null)
                        .await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
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

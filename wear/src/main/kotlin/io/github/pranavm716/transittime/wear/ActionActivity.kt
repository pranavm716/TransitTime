package io.github.pranavm716.transittime.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
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

        if (action == "open_app") {
            Log.d("LiveNotif", "ActionActivity: open_app received, showing status")
            val tv = TextView(this).apply {
                text = "Go Mode Active\nSwipe to dismiss"
                gravity = Gravity.CENTER
                textSize = 18f
            }
            setContentView(tv)
            return
        }

        if (action == "open_stop") {
            val stopId = intent.getStringExtra("stopId")
            Log.d("ActionActivity", "open_stop received for stopId=$stopId")
            if (stopId != null) {
                val cache = WearLocalCache(this)
                cache.saveCurrentStopId(stopId)
                val stopIds = cache.getStopIds()
                val index = stopIds.indexOf(stopId)
                if (index != -1) {
                    cache.saveCurrentIndex(index)
                }
                TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
            }
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                return
            }
        }

        startAction()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAction()
        } else {
            finish()
        }
    }

    private fun startAction() {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: run { finish(); return }

        Log.d("LiveNotif", "ActionActivity.startAction: action=$action, triggering service update")
        GoModeNotificationService.update(this@ActionActivity)

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
                        cache.setLocalGoModeOverride(true)
                        cache.setRefreshing(currentStopId, true)
                    } else {
                        cache.setLocalGoModeOverride(false)
                        cache.setRefreshing(currentStopId, false)
                    }
                    GoModeNotificationService.update(this@ActionActivity)
                }

                val nodes = Wearable.getNodeClient(this@ActionActivity).connectedNodes.await()
                Log.d("ActionActivity", "connectedNodes=${nodes.map { it.displayName }}")
                val phone = nodes.firstOrNull()
                if (phone != null) {
                    Log.d("ActionActivity", "sending message path=$action to phone=${phone.displayName}")
                    val payload = if (action == "/action/go_mode_toggle") {
                        if (cache.getLocalGoModeOverride() == true) {
                            Log.d("LiveNotif", "go_mode_toggle activating for stopId=$currentStopId")
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

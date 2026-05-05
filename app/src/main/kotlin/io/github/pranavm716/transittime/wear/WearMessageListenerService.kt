package io.github.pranavm716.transittime.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.pranavm716.transittime.GoModeManager
import io.github.pranavm716.transittime.widget.TransitWidget

class WearMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d("WearMsgListener", "onMessageReceived: path=${messageEvent.path}")
        when (messageEvent.path) {
            "/action/refresh" -> {
                Log.d("WearMsgListener", "refresh requested — triggering fetch")
                TransitWidget.triggerFetch(this)
            }
            "/action/go_mode_toggle" -> {
                Log.d("WearMsgListener", "go_mode_toggle received")
                val goModeManager = GoModeManager(this)
                goModeManager.goModeExpiresAt = if (goModeManager.isGoModeActive) {
                    0L
                } else {
                    System.currentTimeMillis() + GoModeManager.GO_MODE_DURATION_MS
                }
            }
        }
    }
}

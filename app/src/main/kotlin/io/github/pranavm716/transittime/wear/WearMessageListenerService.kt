package io.github.pranavm716.transittime.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.pranavm716.transittime.GoModeManager
import io.github.pranavm716.transittime.widget.TransitWidget

class WearMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/action/refresh" -> TransitWidget.triggerFetch(this)
            "/action/go_mode_toggle" -> {
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
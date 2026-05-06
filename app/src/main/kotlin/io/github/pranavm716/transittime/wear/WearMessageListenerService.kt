package io.github.pranavm716.transittime.wear

import android.content.Intent
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
                if (GoModeManager(this).isGoModeActive) {
                    Log.d("WearMsgListener", "refresh requested but go mode active — ignoring")
                } else {
                    Log.d("WearMsgListener", "refresh requested — triggering fetch")
                    TransitWidget.triggerFetch(this)
                }
            }
            "/action/go_mode_toggle" -> {
                Log.d("WearMsgListener", "go_mode_toggle received — broadcasting ACTION_TOGGLE_GO_MODE")
                sendBroadcast(
                    Intent(TransitWidget.ACTION_TOGGLE_GO_MODE).setPackage(packageName)
                )
            }
        }
    }
}

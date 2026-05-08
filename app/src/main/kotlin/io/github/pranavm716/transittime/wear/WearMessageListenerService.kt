package io.github.pranavm716.transittime.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.pranavm716.transittime.widget.TransitWidget

class WearMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d("WearMsgListener", "onMessageReceived: path=${messageEvent.path}")
        when (messageEvent.path) {
            "/action/refresh" -> {
                Log.d("WearMsgListener", "refresh requested — triggering fetch")
                TransitWidget.triggerFetch(this)
            }
        }
    }
}

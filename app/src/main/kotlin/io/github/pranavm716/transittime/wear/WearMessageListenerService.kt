package io.github.pranavm716.transittime.wear

import android.appwidget.AppWidgetManager
import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.widget.TransitWidget
import kotlinx.coroutines.runBlocking

class WearMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/action/refresh" -> {
                TransitWidget.triggerFetch(this)
            }
            "/action/go_mode_toggle" -> {
                val stopId = messageEvent.data?.toString(Charsets.UTF_8)
                val widgetId = if (stopId != null) runBlocking {
                    TransitDatabase.getInstance(this@WearMessageListenerService)
                        .widgetConfigDao()
                        .getAllConfigs()
                        .firstOrNull { it.stopId == stopId }
                        ?.widgetId
                } else null
                sendBroadcast(
                    Intent(TransitWidget.ACTION_TOGGLE_GO_MODE).setPackage(packageName).apply {
                        putExtra(
                            TransitWidget.EXTRA_WIDGET_ID,
                            widgetId ?: AppWidgetManager.INVALID_APPWIDGET_ID
                        )
                    }
                )
            }
        }
    }
}

package io.github.pranavm716.transittime.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.tiles.TileService

class StopPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cache = WearLocalCache(this)
        val stopIds = cache.getStopIds()
        val stops = stopIds.mapNotNull { id -> cache.getSnapshot(id)?.stopName?.let { id to it } }

        setContent {
            MaterialTheme {
                StopPickerScreen(stops) { stopId ->
                    val index = stopIds.indexOf(stopId)
                    cache.saveCurrentStopId(stopId)
                    if (index != -1) cache.saveCurrentIndex(index)
                    TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                    finish()
                }
            }
        }
    }
}

@Composable
private fun StopPickerScreen(
    stops: List<Pair<String, String>>,
    onStopSelected: (stopId: String) -> Unit
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Select Stop",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        itemsIndexed(stops) { _, (stopId, stopName) ->
            Chip(
                onClick = { onStopSelected(stopId) },
                label = { Text(stopName) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

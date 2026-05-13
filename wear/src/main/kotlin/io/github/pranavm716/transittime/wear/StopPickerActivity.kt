package io.github.pranavm716.transittime.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.tiles.TileService

private val BgMain = Color(0xFF0D1117)
private val BgContainer = Color(0xFF161B22)
private val Accent = Color(0xFF238636)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

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
    val listState = rememberScalingLazyListState()
    Scaffold(
        modifier = Modifier.background(BgMain),
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(BgMain)
        ) {
            item {
                Text(
                    text = "Select Stop",
                    color = TextSecondary,
                    style = MaterialTheme.typography.title3,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            itemsIndexed(stops) { _, (stopId, stopName) ->
                Chip(
                    onClick = { onStopSelected(stopId) },
                    label = { Text(stopName, color = TextPrimary, fontWeight = FontWeight.Normal) },
                    colors = ChipDefaults.chipColors(backgroundColor = BgContainer),
                    border = ChipDefaults.chipBorder(borderStroke = BorderStroke(1.dp, Accent)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.padding(horizontal = 10.dp).fillMaxWidth()
                )
            }
        }
    }
}

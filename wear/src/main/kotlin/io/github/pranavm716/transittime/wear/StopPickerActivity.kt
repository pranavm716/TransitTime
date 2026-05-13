package io.github.pranavm716.transittime.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.pranavm716.transittime.data.model.Agency

private val BgMain = Color(0xFF0D1117)
private val BgContainer = Color(0xFF161B22)
private val Accent = Color(0xFF238636)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

private val LOGO_GAP = 14.dp
private val CHIP_HEIGHT = 64.dp
private val MAX_FONT_SIZE = 15.sp
private val MIN_FONT_SIZE = 10.sp

class StopPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cache = WearLocalCache(this)

        setContent {
            MaterialTheme {
                val stops = rememberLiveStops(cache)
                StopPickerScreen(stops) { stopId ->
                    val index = cache.getStopIds().indexOf(stopId)
                    cache.saveCurrentStopId(stopId)
                    if (index != -1) cache.saveCurrentIndex(index)
                    TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                    finish()
                }
            }
        }
    }
}

private fun buildStops(cache: WearLocalCache): List<Triple<String, String, Agency>> =
    cache.getStopIds().mapNotNull { id -> cache.getSnapshot(id)?.let { Triple(id, it.stopName, it.agency) } }

@Composable
private fun rememberLiveStops(cache: WearLocalCache): List<Triple<String, String, Agency>> {
    var stops by remember { mutableStateOf(buildStops(cache)) }
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            stops = buildStops(cache)
        }
        cache.registerListener(listener)
        onDispose { cache.unregisterListener(listener) }
    }
    return stops
}

@Composable
private fun AutoSizeText(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = MAX_FONT_SIZE,
    minFontSize: TextUnit = MIN_FONT_SIZE,
) {
    var fontSize by remember(text) { mutableStateOf(maxFontSize) }
    var readyToDraw by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        style = TextStyle(
            color = color,
            fontWeight = fontWeight,
            fontSize = fontSize
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.drawWithContent { if (readyToDraw) drawContent() },
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > minFontSize) {
                fontSize = maxOf(fontSize.value - 1f, minFontSize.value).sp
                readyToDraw = false
            } else {
                readyToDraw = true
            }
        }
    )
}

@Composable
private fun StopPickerScreen(
    stops: List<Triple<String, String, Agency>>,
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
            itemsIndexed(stops) { _, (stopId, stopName, agency) ->
                val logoRes = when (agency) {
                    Agency.BART -> R.drawable.ic_bart
                    Agency.MUNI -> R.drawable.ic_muni
                    Agency.CALTRAIN -> R.drawable.ic_caltrain
                }
                Chip(
                    onClick = { onStopSelected(stopId) },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(LOGO_GAP)
                        ) {
                            Image(
                                painter = painterResource(logoRes),
                                contentDescription = null,
                                modifier = Modifier.size(width = 28.dp, height = 13.dp)
                            )
                            AutoSizeText(
                                text = stopName,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = BgContainer),
                    border = ChipDefaults.chipBorder(borderStroke = BorderStroke(1.dp, Accent)),
                    contentPadding = PaddingValues(horizontal = LOGO_GAP, vertical = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .fillMaxWidth()
                        .height(CHIP_HEIGHT)
                )
            }
        }
    }
}

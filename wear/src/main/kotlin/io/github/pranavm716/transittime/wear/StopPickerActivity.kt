package io.github.pranavm716.transittime.wear

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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

private val BgMain = Color(0xFF000000)
private val BgContainer = Color(0xFF000000)
private val BgDragging = Color(0xFF1A2332)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val ChipBorder = Color(0xFF475569)

private val LOGO_GAP = 14.dp
private val CHIP_HEIGHT = 64.dp
private val CHIP_SPACING = 8.dp
private val MAX_FONT_SIZE = 15.sp
private val MIN_FONT_SIZE = 10.sp

class StopPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cache = WearLocalCache(this)

        setContent {
            MaterialTheme {
                val stops = rememberLiveStops(cache)
                val initialIndex = remember {
                    val currentId = cache.getCurrentStopId()
                    buildStops(cache).indexOfFirst { it.first == currentId }.coerceAtLeast(0) + 1
                }
                StopPickerScreen(
                    stops = stops,
                    initialCenterItemIndex = initialIndex,
                    onStopSelected = { stopId ->
                        val index = cache.getStopIds().indexOf(stopId)
                        cache.saveCurrentStopId(stopId)
                        if (index != -1) cache.saveCurrentIndex(index)
                        TileService.getUpdater(this).requestUpdate(TransitTileService::class.java)
                        finish()
                    },
                    onOrderChanged = { orderedIds -> cache.saveWatchStopOrder(orderedIds) }
                )
            }
        }
    }
}

private fun buildStops(cache: WearLocalCache): List<Triple<String, String, Agency>> {
    val all = cache.getStopIds()
        .mapNotNull { id -> cache.getSnapshot(id)?.let { Triple(id, it.stopName, it.agency) } }
    val customOrder = cache.getWatchStopOrder()
    return if (customOrder != null) {
        val map = all.associateBy { it.first }
        val ordered = customOrder.mapNotNull { map[it] }
        val remaining = all.filter { it.first !in customOrder.toSet() }
            .sortedWith(compareBy({ it.third.ordinal }, { it.second }))
        ordered + remaining
    } else {
        all.sortedWith(compareBy({ it.third.ordinal }, { it.second }))
    }
}

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
        style = TextStyle(color = color, fontWeight = fontWeight, fontSize = fontSize),
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
    initialCenterItemIndex: Int,
    onStopSelected: (stopId: String) -> Unit,
    onOrderChanged: (List<String>) -> Unit,
) {
    val itemsState = remember { mutableStateOf(stops) }
    LaunchedEffect(stops) { itemsState.value = stops }

    val draggingIndexState = remember { mutableStateOf<Int?>(null) }
    val draggingOffsetState = remember { mutableStateOf(0f) }

    val listState = rememberScalingLazyListState(initialCenterItemIndex = initialCenterItemIndex)

    val density = LocalDensity.current
    val chipStepPx = with(density) { (CHIP_HEIGHT + CHIP_SPACING).toPx() }

    Scaffold(
        modifier = Modifier.background(BgMain),
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(CHIP_SPACING),
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
            itemsIndexed(
                itemsState.value,
                key = { _, (stopId, _, _) -> stopId }
            ) { _, (stopId, stopName, agency) ->
                val isDragging = draggingIndexState.value
                    ?.let { itemsState.value.getOrNull(it)?.first == stopId } == true
                val chipScale by animateFloatAsState(
                    targetValue = if (isDragging) 1.06f else 1.0f,
                    label = "chipScale"
                )
                val haptic = LocalHapticFeedback.current
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
                                modifier = Modifier.size(width = 36.dp, height = 17.dp)
                            )
                            AutoSizeText(
                                text = stopName,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (isDragging) BgDragging else BgContainer
                    ),
                    border = ChipDefaults.chipBorder(
                        borderStroke = BorderStroke(1.dp, ChipBorder)
                    ),
                    contentPadding = PaddingValues(horizontal = LOGO_GAP, vertical = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .fillMaxWidth()
                        .height(CHIP_HEIGHT)
                        .scale(chipScale)
                        .pointerInput(stopId) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingIndexState.value =
                                        itemsState.value.indexOfFirst { it.first == stopId }
                                    draggingOffsetState.value = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    draggingOffsetState.value += dragAmount.y
                                    val dIdx = draggingIndexState.value
                                        ?: return@detectDragGesturesAfterLongPress
                                    val half = chipStepPx / 2f
                                    when {
                                        draggingOffsetState.value > half &&
                                                dIdx < itemsState.value.size - 1 -> {
                                            itemsState.value = itemsState.value.toMutableList()
                                                .apply { add(dIdx + 1, removeAt(dIdx)) }
                                            draggingIndexState.value = dIdx + 1
                                            draggingOffsetState.value -= chipStepPx
                                        }

                                        draggingOffsetState.value < -half && dIdx > 0 -> {
                                            itemsState.value = itemsState.value.toMutableList()
                                                .apply { add(dIdx - 1, removeAt(dIdx)) }
                                            draggingIndexState.value = dIdx - 1
                                            draggingOffsetState.value += chipStepPx
                                        }
                                    }
                                },
                                onDragEnd = {
                                    onOrderChanged(itemsState.value.map { it.first })
                                    draggingIndexState.value = null
                                    draggingOffsetState.value = 0f
                                },
                                onDragCancel = {
                                    draggingIndexState.value = null
                                    draggingOffsetState.value = 0f
                                }
                            )
                        }
                )
            }
        }
    }
}

package io.github.pranavm716.transittime.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.TileBuilders
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransitTileRenderer {

    private val DIM_COLOR = 0xFFAAAAAA.toInt()

    // Use bright colors to avoid "dim" look
    private val COLORS = Colors(
        0xFFB3E5FC.toInt(), // primary: Light Blue
        0xFF000000.toInt(), // onPrimary: Black
        0xFF121212.toInt(), // surface: Dark Grey
        0xFFFFFFFF.toInt()  // onSurface: White (Bright)
    )

    fun renderTile(
        context: Context,
        deviceConfiguration: DeviceParametersBuilders.DeviceParameters,
        stopName: String,
        currentIndex: Int,
        nextIndex: Int,
        totalConfigs: Int,
        departures: List<io.github.pranavm716.transittime.model.WatchDeparture> = emptyList(),
        fetchedAt: Long = 0L
    ): TileBuilders.Tile {
        val now = System.currentTimeMillis()
        val nextDepText = departures.firstOrNull()?.let {
            val time = io.github.pranavm716.transittime.util.getDisplayTime(
                arrivalTimestamp = it.arrivalTimestamp,
                departureTimestamp = it.departureTimestamp,
                isOriginStop = it.isOriginStop,
                isScheduled = it.isScheduled,
                now = now
            )
            "${it.routeName}: $time"
        } ?: "No departures"

        val updatedText = if (fetchedAt > 0L)
            "Updated " + SimpleDateFormat("h:mma", Locale.getDefault()).format(Date(fetchedAt))
        else null

        val root = if (totalConfigs > 1) {
            val columnBuilder = LayoutElementBuilders.Column.Builder()
            if (updatedText != null) {
                columnBuilder.addContent(
                    Text.Builder(context, updatedText)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(ColorBuilders.argb(DIM_COLOR))
                        .build()
                )
                columnBuilder.addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setHeight(DimensionBuilders.dp(2f))
                        .build()
                )
            }
            columnBuilder
                .addContent(
                    Text.Builder(context, stopName)
                        .setTypography(Typography.TYPOGRAPHY_TITLE2)
                        .setColor(ColorBuilders.argb(COLORS.onSurface))
                        .setMaxLines(2)
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setHeight(DimensionBuilders.dp(4f))
                        .build()
                )
                .addContent(
                    Text.Builder(context, nextDepText)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(ColorBuilders.argb(COLORS.onSurface))
                        .build()
                )

            PrimaryLayout.Builder(deviceConfiguration)
                .setResponsiveContentInsetEnabled(true)
                .setPrimaryLabelTextContent(
                    Text.Builder(context, "Stop ${currentIndex + 1} of $totalConfigs")
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(ColorBuilders.argb(COLORS.onSurface))
                        .build()
                )
                .setContent(columnBuilder.build())
                .setPrimaryChipContent(
                    CompactChip.Builder(
                        context,
                        "Next",
                        ModifiersBuilders.Clickable.Builder()
                            .setId(nextIndex.toString())
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build(),
                        deviceConfiguration
                    ).build()
                )
                .build()
        } else {
            // Single stop or no stops
            val layout = PrimaryLayout.Builder(deviceConfiguration)
                .setResponsiveContentInsetEnabled(true)
            if (updatedText != null) {
                layout.setPrimaryLabelTextContent(
                    Text.Builder(context, updatedText)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(ColorBuilders.argb(DIM_COLOR))
                        .build()
                )
            }
            layout.setContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        Text.Builder(context, stopName)
                            .setTypography(Typography.TYPOGRAPHY_TITLE2)
                            .setColor(ColorBuilders.argb(COLORS.onSurface))
                            .setMaxLines(2)
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(DimensionBuilders.dp(4f))
                            .build()
                    )
                    .addContent(
                        Text.Builder(context, nextDepText)
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(ColorBuilders.argb(COLORS.onSurface))
                            .build()
                    )
                    .build()
            )
            layout.build()
        }

        return TileBuilders.Tile.Builder()
            .setResourcesVersion("0")
            .setFreshnessIntervalMillis(30_000L)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(root)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }
}

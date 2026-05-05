package io.github.pranavm716.transittime.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.TileBuilders
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.model.IconShape
import io.github.pranavm716.transittime.model.TileRow
import io.github.pranavm716.transittime.model.TileSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransitTileRenderer {

    const val RESOURCES_VERSION = "1"

    // Colors matching :app colors.xml
    private val COLOR_BG = 0xFF000000.toInt()
    private val COLOR_DIVIDER = 0xFFBDC1C7.toInt()
    private val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private val COLOR_DIM = 0xFFAAAAAA.toInt()
    private val COLOR_GO_MODE = 0xFF238636.toInt()
    private val COLOR_ERROR = 0xFFdc3545.toInt()
    private val COLOR_ICON_FB = 0xFF888888.toInt()

    fun renderTile(
        context: Context,
        deviceConfiguration: DeviceParametersBuilders.DeviceParameters,
        snapshot: TileSnapshot,
        currentIndex: Int,
        nextIndex: Int,
        totalStops: Int
    ): TileBuilders.Tile =
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(30_000L)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(
                                        buildRoot(
                                            context,
                                            deviceConfiguration,
                                            snapshot,
                                            nextIndex
                                        )
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    // ── Root ─────────────────────────────────────────────────────────────────

    private fun buildRoot(
        context: Context,
        device: DeviceParametersBuilders.DeviceParameters,
        snapshot: TileSnapshot,
        nextIndex: Int
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(COLOR_BG))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHeight(DimensionBuilders.expand())
                    .addContent(buildHeader(context, device, snapshot, nextIndex))
                    .addContent(buildContent(context, device, snapshot))
                    .addContent(buildFooter(context, device, snapshot))
                    .build()
            )
            .build()

    // ── Header ───────────────────────────────────────────────────────────────

    private fun buildHeader(
        context: Context,
        device: DeviceParametersBuilders.DeviceParameters,
        snapshot: TileSnapshot,
        nextIndex: Int
    ): LayoutElementBuilders.LayoutElement {
        val logoId = when (snapshot.agency) {
            Agency.BART -> "ic_bart"
            Agency.MUNI -> "ic_muni"
            Agency.CALTRAIN -> "ic_caltrain"
        }
        val stopNameSp = when {
            snapshot.stopName.length <= 16 -> 17f
            snapshot.stopName.length <= 24 -> 15f
            else -> 13f
        }
        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(nextIndex.toString())
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build()
                    )
                    .setPadding(edgePadding(device, top = 0f, isHeaderFooter = true))
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(logoId)
                    .setWidth(DimensionBuilders.dp(32f))
                    .setHeight(DimensionBuilders.dp(15f))
                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                    .build()
            )
            .addContent(vSpacer(4f))
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(strProp(snapshot.stopName))
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(ColorBuilders.argb(COLOR_WHITE))
                            .setSize(DimensionBuilders.sp(stopNameSp))
                            .setWeight(boldWeight())
                            .build()
                    )
                    .setMaxLines(intProp(2))
                    .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .build()
            )
            .addContent(vSpacer(6f))
            .build()
    }

    // ── Content ──────────────────────────────────────────────────────────────

    private fun buildContent(
        context: Context,
        device: DeviceParametersBuilders.DeviceParameters,
        snapshot: TileSnapshot
    ): LayoutElementBuilders.LayoutElement {
        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("refresh")
                            .setOnClick(launchAction(context, "/action/refresh"))
                            .build()
                    )
                    .setPadding(edgePadding(device, start = 8f, end = 8f, top = 4f))
                    .build()
            )

        val errorLabel = snapshot.errorLabel
        when {
            errorLabel != null -> {
                col.addContent(plainText(errorLabel, COLOR_ERROR, 13f))
            }

            snapshot.rows.isEmpty() -> {
                col.addContent(plainText("No departures", COLOR_DIM, 13f))
            }

            else -> {
                val visible = snapshot.rows.take(3)
                visible.forEachIndexed { i, row ->
                    col.addContent(buildDepartureRow(device, row))
                    if (i < visible.lastIndex) col.addContent(vSpacer(3f))
                }
                val overflow = snapshot.rows.size - 3
                if (overflow > 0) {
                    col.addContent(vSpacer(3f))
                    col.addContent(
                        plainText(
                            "+$overflow more route${if (overflow > 1) "s" else ""}",
                            COLOR_DIM, 13f
                        )
                    )
                }
            }
        }

        return col.build()
    }

    private fun buildDepartureRow(
        device: DeviceParametersBuilders.DeviceParameters,
        row: TileRow
    ): LayoutElementBuilders.LayoutElement {
        val iconText = row.iconText?.takeIf { it.isNotEmpty() } ?: "?"
        val iconBgColor = if (row.iconBgColor != 0) row.iconBgColor else COLOR_ICON_FB
        val iconTxtColor = if (row.iconTextColor != 0) row.iconTextColor else COLOR_WHITE
        val iconShape = row.iconShape ?: IconShape.SQUARE

        // Scale text size to icon text length, matching RouteIconDrawer ratios.
        val iconTextSp = when (iconText.length) {
            1 -> 11f; 2 -> 9f; else -> 7f
        }

        // Icon dimensions + corner radius, matching RouteIconDrawer geometry at 24dp.
        val icon = when (iconShape) {
            IconShape.SQUARE -> iconBox(
                iconText,
                iconBgColor,
                iconTxtColor,
                iconTextSp,
                24f,
                24f,
                24f * 0.08f
            )

            IconShape.CIRCLE -> iconBox(
                iconText,
                iconBgColor,
                iconTxtColor,
                iconTextSp,
                24f,
                24f,
                12f
            )

            IconShape.ROUNDED_RECT -> iconBox(
                iconText,
                iconBgColor,
                iconTxtColor,
                iconTextSp,
                24f,
                24f,
                24f * 0.35f
            )
            // RECT: sharp-edged horizontal bar (32×20dp), no rounding.
            IconShape.RECT -> iconBox(iconText, iconBgColor, iconTxtColor, iconTextSp, 32f, 20f, 0f)
        }

        val headsign = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(strProp(row.headsign))
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(ColorBuilders.argb(COLOR_WHITE))
                            .setSize(DimensionBuilders.sp(13f))
                            .build()
                    )
                    .setMaxLines(intProp(1))
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .build()
            )
            .build()

        val rowBuilder = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(icon)
            .addContent(hSpacer(4f))
            .addContent(headsign)

        repeat(3) { i ->
            rowBuilder.addContent(
                timeCell(
                    row.displayTimes.getOrNull(i) ?: "—",
                    row.delayColors.getOrNull(i) ?: COLOR_DIM
                )
            )
        }

        return rowBuilder.build()
    }

    // ── Footer ───────────────────────────────────────────────────────────────

    private fun buildFooter(
        context: Context,
        device: DeviceParametersBuilders.DeviceParameters,
        snapshot: TileSnapshot
    ): LayoutElementBuilders.LayoutElement {
        val timestamp = if (snapshot.fetchedAt > 0L)
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(snapshot.fetchedAt))
        else "—"

        val tsColor = when {
            snapshot.errorLabel != null -> COLOR_ERROR
            snapshot.goModeActive -> COLOR_GO_MODE
            else -> COLOR_DIM
        }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("go_mode")
                            .setOnClick(launchAction(context, "/action/go_mode_toggle"))
                            .build()
                    )
                    .setPadding(edgePadding(device, top = 4f, bottom = 4f, isHeaderFooter = true))
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setWidth(DimensionBuilders.wrap())
                    .setHeight(DimensionBuilders.wrap())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(plainText(timestamp, tsColor, 13f))
                    .addContent(hSpacer(4f))
                    .addContent(
                        LayoutElementBuilders.Image.Builder()
                            .setResourceId(
                                if (snapshot.goModeActive) "ic_go_mode_dot" else "ic_refresh"
                            )
                            .setWidth(DimensionBuilders.dp(14f))
                            .setHeight(DimensionBuilders.dp(14f))
                            .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun edgePadding(
        device: DeviceParametersBuilders.DeviceParameters,
        top: Float = 0f,
        bottom: Float = 0f,
        start: Float = 0f,
        end: Float = 0f,
        isHeaderFooter: Boolean = false
    ): ModifiersBuilders.Padding {
        val isRound = device.screenShape == DeviceParametersBuilders.SCREEN_SHAPE_ROUND
        if (!isRound) {
            return ModifiersBuilders.Padding.Builder()
                .setTop(DimensionBuilders.dp(top))
                .setBottom(DimensionBuilders.dp(bottom))
                .setStart(DimensionBuilders.dp(start))
                .setEnd(DimensionBuilders.dp(end))
                .build()
        }

        // On round screens, headers and footers need significantly more horizontal padding
        // because the circle is narrowest at the top and bottom.
        // We use 30dp to ensure 2-line stop names don't clip on Galaxy Watch 7.
        val h = if (isHeaderFooter) 30f else 12f

        // Vertical "safe zones" for round screens.
        // Reverting top gap to 4dp as requested ("keep spacing above logo the same").
        val t = if (isHeaderFooter && top <= 2f) top + 4f else top + 6f
        val b = if (isHeaderFooter) bottom + 12f else bottom

        return ModifiersBuilders.Padding.Builder()
            .setTop(DimensionBuilders.dp(t))
            .setBottom(DimensionBuilders.dp(b))
            .setStart(DimensionBuilders.dp(start + h))
            .setEnd(DimensionBuilders.dp(end + h))
            .build()
    }

    private fun iconBox(
        text: String,
        bgColor: Int,
        textColor: Int,
        textSizeSp: Float,
        widthDp: Float,
        heightDp: Float,
        cornerRadiusDp: Float
    ): LayoutElementBuilders.LayoutElement {
        val bg = ModifiersBuilders.Background.Builder()
            .setColor(ColorBuilders.argb(bgColor))
        if (cornerRadiusDp > 0f) {
            bg.setCorner(
                ModifiersBuilders.Corner.Builder()
                    .setRadius(DimensionBuilders.dp(cornerRadiusDp))
                    .build()
            )
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(widthDp))
            .setHeight(DimensionBuilders.dp(heightDp))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(bg.build())
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(strProp(text))
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(ColorBuilders.argb(textColor))
                            .setSize(DimensionBuilders.sp(textSizeSp))
                            .setWeight(boldWeight())
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun timeCell(time: String, color: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(36f))
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(strProp(time))
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(ColorBuilders.argb(color))
                            .setSize(DimensionBuilders.sp(13f))
                            .setWeight(boldWeight())
                            .build()
                    )
                    .build()
            )
            .build()

    private fun plainText(
        text: String,
        color: Int,
        sizeSp: Float
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(strProp(text))
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setColor(ColorBuilders.argb(color))
                    .setSize(DimensionBuilders.sp(sizeSp))
                    .build()
            )
            .build()

    private fun divider(): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.dp(1f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(COLOR_DIVIDER))
                            .build()
                    )
                    .build()
            )
            .build()

    private fun vSpacer(dp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder()
            .setWidth(DimensionBuilders.dp(1f))
            .setHeight(DimensionBuilders.dp(dp))
            .build()

    private fun hSpacer(dp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder()
            .setWidth(DimensionBuilders.dp(dp))
            .setHeight(DimensionBuilders.dp(1f))
            .build()

    private fun launchAction(context: Context, path: String): ActionBuilders.Action =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName("io.github.pranavm716.transittime.wear.ActionActivity")
                    .addKeyToExtraMapping(
                        ActionActivity.EXTRA_ACTION,
                        ActionBuilders.AndroidStringExtra.Builder()
                            .setValue(path)
                            .build()
                    )
                    .build()
            )
            .build()

    // Prop wrappers ───────────────────────────────────────────────────────────

    private fun strProp(value: String): TypeBuilders.StringProp =
        TypeBuilders.StringProp.Builder().setValue(value).build()

    private fun intProp(value: Int): TypeBuilders.Int32Prop =
        TypeBuilders.Int32Prop.Builder().setValue(value).build()

    private fun boldWeight(): LayoutElementBuilders.FontWeightProp =
        LayoutElementBuilders.FontWeightProp.Builder()
            .setValue(LayoutElementBuilders.FONT_WEIGHT_BOLD)
            .build()
}

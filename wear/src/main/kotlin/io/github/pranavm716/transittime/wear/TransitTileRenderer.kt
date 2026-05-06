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
import androidx.wear.protolayout.expression.AnimationParameterBuilders
import androidx.wear.protolayout.expression.DynamicBuilders
import androidx.wear.protolayout.expression.ProtoLayoutExperimental
import androidx.wear.tiles.TileBuilders
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.model.IconShape
import io.github.pranavm716.transittime.model.TileRow
import io.github.pranavm716.transittime.model.TileSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ProtoLayoutExperimental::class)
object TransitTileRenderer {

    const val RESOURCES_VERSION = "1"

    // Colors matching :app colors.xml
    private val COLOR_BG = 0xFF000000.toInt()
    private val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private val COLOR_DIM = 0xFFAAAAAA.toInt()
    private val COLOR_GO_MODE = 0xFF238636.toInt()
    private val COLOR_ERROR = 0xFFdc3545.toInt()
    private val COLOR_ICON_FB = 0xFF888888.toInt()
    private val COLOR_RIPPLE = 0x33FFFFFF

    fun renderTile(
        context: Context,
        deviceConfiguration: DeviceParametersBuilders.DeviceParameters,
        snapshot: TileSnapshot,
        currentIndex: Int,
        prevIndex: Int,
        nextIndex: Int,
        totalStops: Int
    ): TileBuilders.Tile {
        val root = if (totalStops == 0) buildNoStopsLayout() else buildRoot(
            context, deviceConfiguration, snapshot, nextIndex, currentIndex, prevIndex, totalStops
        )
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(15 * 60 * 1000L)
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

    // ── No stops layout ──────────────────────────────────────────────────────

    private fun buildNoStopsLayout(): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
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
                LayoutElementBuilders.Text.Builder()
                    .setText(strProp("No stops configured. To get started, add a TransitTime widget on your phone."))
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(ColorBuilders.argb(COLOR_DIM))
                            .setSize(DimensionBuilders.sp(13f))
                            .build()
                    )
                    .setMaxLines(intProp(6))
                    .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .build()
            )
            .build()

    // ── Root ─────────────────────────────────────────────────────────────────

    private fun buildRoot(
        context: Context,
        device: DeviceParametersBuilders.DeviceParameters,
        snapshot: TileSnapshot,
        nextIndex: Int,
        currentIndex: Int,
        prevIndex: Int,
        totalStops: Int
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
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(buildHeader(context, device, snapshot, nextIndex))
                    .addContent(buildContent(context, device, snapshot))
                    .addContent(buildFooter(context, device, snapshot))
                    .build()
            )
            .addContent(buildStopIndicatorArc(currentIndex, prevIndex, totalStops))
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
            snapshot.stopName.length <= 14 -> 16f
            snapshot.stopName.length <= 22 -> 14f
            snapshot.stopName.length <= 30 -> 12f
            else -> 11f
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
                            .setVisualFeedbackEnabled(true)
                            .build()
                    )
                    .setPadding(edgePadding(device, top = 4f, isHeaderFooter = true))
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
            .addContent(vSpacer(8f))
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
            .build()
    }

    // ── Content ──────────────────────────────────────────────────────────────

    private fun buildContent(
        context: Context,
        device: DeviceParametersBuilders.DeviceParameters,
        snapshot: TileSnapshot
    ): LayoutElementBuilders.LayoutElement {
        val rowsCol = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        when {
            snapshot.rows.isEmpty() -> {
                rowsCol.addContent(plainText("No departures found", COLOR_DIM, 13f))
            }

            else -> {
                val visible = snapshot.rows.take(3)
                // Compute available horizontal space
                val isRound = device.screenShape == DeviceParametersBuilders.SCREEN_SHAPE_ROUND
                val sidePaddingDp = if (isRound) 24f else 0f
                val iconAreaDp = 42f  // 34dp icon box + 8dp spacer
                val timesAvailableDp = device.screenWidthDp - 2 * sidePaddingDp - iconAreaDp

                val gapDp = 10f

                visible.forEachIndexed { i, row ->
                    rowsCol.addContent(buildDepartureRow(device, row, gapDp))
                    if (i < visible.lastIndex) rowsCol.addContent(vSpacer(6f))
                }
                val overflow = snapshot.rows.size - 3
                if (overflow > 0) {
                    rowsCol.addContent(vSpacer(3f))
                    rowsCol.addContent(
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(DimensionBuilders.expand())
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                            .addContent(
                                plainText(
                                    "+$overflow more route${if (overflow > 1) "s" else ""}",
                                    0xFF666666.toInt(), 11f
                                )
                            )
                            .build()
                    )
                }
            }
        }

        val hasRows = snapshot.rows.isNotEmpty()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setVerticalAlignment(
                if (hasRows) LayoutElementBuilders.VERTICAL_ALIGN_TOP
                else LayoutElementBuilders.VERTICAL_ALIGN_CENTER
            )
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("refresh")
                            .setOnClick(launchAction(context, "/action/refresh"))
                            .setVisualFeedbackEnabled(true)
                            .build()
                    )
                    .setPadding(edgePadding(device, top = 0f, isHeaderFooter = false))
                    .build()
            )
            .addContent(rowsCol.build())
            .build()
    }

    private fun buildDepartureRow(
        device: DeviceParametersBuilders.DeviceParameters,
        row: TileRow,
        gapDp: Float
    ): LayoutElementBuilders.LayoutElement {
        val iconText = row.iconText?.takeIf { it.isNotEmpty() } ?: "?"
        val iconBgColor = row.iconBgColor
        val iconTxtColor = if (row.iconTextColor != 0) row.iconTextColor else COLOR_WHITE
        val iconShape = row.iconShape ?: IconShape.SQUARE

        val badgeWidth: Float
        val badgeHeight: Float
        val cornerRadius: Float
        val fontSize: Float

        when (iconShape) {
            IconShape.SQUARE -> {
                badgeWidth = 28f
                badgeHeight = 28f
                cornerRadius = 5f
                fontSize = 13f
            }

            IconShape.CIRCLE -> {
                badgeWidth = 28f
                badgeHeight = 28f
                cornerRadius = 14f
                fontSize = 13f
            }

            IconShape.ROUNDED_RECT -> {
                badgeWidth = 28f
                badgeHeight = 28f
                cornerRadius = 10f
                fontSize = 13f
            }

            IconShape.RECT -> {
                badgeWidth = 34f
                badgeHeight = 20f
                cornerRadius = 0f
                fontSize = 10f
            }
        }

        val adjustedFontSize = when {
            iconText.length <= 2 -> fontSize
            iconText.length == 3 -> fontSize * 0.85f
            else -> fontSize * 0.69f
        }

        val icon = iconBox(
            iconText,
            iconBgColor,
            iconTxtColor,
            adjustedFontSize,
            badgeWidth,
            badgeHeight,
            cornerRadius
        )

        val headsign = LayoutElementBuilders.Text.Builder()
            .setText(strProp(row.headsign))
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setColor(ColorBuilders.argb(COLOR_WHITE))
                    .setSize(DimensionBuilders.sp(13f))
                    .setWeight(mediumWeight())
                    .build()
            )
            .setMaxLines(intProp(1))
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .build()

        val timesRow = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setHeight(DimensionBuilders.wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        val times = row.displayTimes.take(3)
        times.forEachIndexed { i, time ->
            timesRow.addContent(timeText(time, row.delayColors.getOrNull(i) ?: COLOR_DIM))
            if (i < times.lastIndex) {
                timesRow.addContent(hSpacer(gapDp))
            }
        }

        val textCol = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .addContent(headsign)
            .addContent(timesRow.build())
            .build()

        return LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.dp(34f))
                    .setHeight(DimensionBuilders.dp(28f))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(icon)
                    .build()
            )
            .addContent(hSpacer(8f))
            .addContent(textCol)
            .build()
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
                            .setVisualFeedbackEnabled(true)
                            .build()
                    )
                    .setPadding(edgePadding(device, top = 4f, bottom = 18f, isHeaderFooter = true))
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setWidth(DimensionBuilders.wrap())
                    .setHeight(DimensionBuilders.wrap())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(plainText(snapshot.errorLabel ?: timestamp, tsColor, 13f))
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

    // ── Stop indicator arc ───────────────────────────────────────────────────

    @OptIn(ProtoLayoutExperimental::class)
    private fun buildStopIndicatorArc(
        currentIndex: Int,
        prevIndex: Int,
        totalStops: Int
    ): LayoutElementBuilders.LayoutElement {
        if (totalStops <= 1) return LayoutElementBuilders.Spacer.Builder()
            .setWidth(DimensionBuilders.dp(0f))
            .setHeight(DimensionBuilders.dp(0f))
            .build()

        val gapDeg = 5f
        val segDeg = ((60f - (totalStops - 1) * gapDeg) / totalStops).coerceAtLeast(1f)
        val pitch = segDeg + gapDeg
        // Arc spans 60° centered at 180° (bottom center), so leftmost segment starts at 150°.
        // Clockwise draw order means stop 0 maps to the highest drawing index (leftmost visually).
        val arcStart = 150f

        fun segCenterAngle(stopIdx: Int): Float {
            val drawIdx = totalStops - 1 - stopIdx
            return arcStart + drawIdx * pitch + segDeg / 2f
        }

        // Background: all dim segments on the fixed track.
        val trackArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(DimensionBuilders.DegreesProp.Builder().setValue(180f).build())
            .setAnchorType(
                LayoutElementBuilders.ArcAnchorTypeProp.Builder()
                    .setValue(LayoutElementBuilders.ARC_ANCHOR_CENTER)
                    .build()
            )
        for (i in 0 until totalStops) {
            if (i > 0) {
                trackArc.addContent(
                    LayoutElementBuilders.ArcSpacer.Builder()
                        .setLength(DimensionBuilders.DegreesProp.Builder().setValue(gapDeg).build())
                        .setThickness(DimensionBuilders.dp(2.5f))
                        .build()
                )
            }
            trackArc.addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(DimensionBuilders.DegreesProp.Builder().setValue(segDeg).build())
                    .setThickness(DimensionBuilders.dp(2.5f))
                    .setColor(ColorBuilders.argb(0x40FFFFFF))
                    .setStrokeCap(
                        LayoutElementBuilders.StrokeCapProp.Builder()
                            .setValue(LayoutElementBuilders.STROKE_CAP_ROUND)
                            .build()
                    )
                    .build()
            )
        }

        // Foreground: single bright segment that slides to the active position.
        val toAngle = segCenterAngle(currentIndex)
        val fromAngle = segCenterAngle(prevIndex)
        // Wrap (N-1 → 0) reverses direction, so snap without animation.
        val isWrap = prevIndex == totalStops - 1 && currentIndex == 0
        val slideSpec = AnimationParameterBuilders.AnimationSpec.Builder()
            .setAnimationParameters(
                AnimationParameterBuilders.AnimationParameters.Builder()
                    .setDurationMillis(200)
                    .build()
            )
            .build()
        val anchorProp = if (!isWrap && fromAngle != toAngle) {
            DimensionBuilders.DegreesProp.Builder()
                .setValue(toAngle)
                .setDynamicValue(
                    DynamicBuilders.DynamicFloat.animate(
                        fromAngle,
                        toAngle,
                        slideSpec
                    )
                )
                .build()
        } else {
            DimensionBuilders.DegreesProp.Builder().setValue(toAngle).build()
        }
        val sliderArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(anchorProp)
            .setAnchorType(
                LayoutElementBuilders.ArcAnchorTypeProp.Builder()
                    .setValue(LayoutElementBuilders.ARC_ANCHOR_CENTER)
                    .build()
            )
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(DimensionBuilders.DegreesProp.Builder().setValue(segDeg).build())
                    .setThickness(DimensionBuilders.dp(2.5f))
                    .setColor(ColorBuilders.argb(COLOR_WHITE))
                    .setStrokeCap(
                        LayoutElementBuilders.StrokeCapProp.Builder()
                            .setValue(LayoutElementBuilders.STROKE_CAP_ROUND)
                            .build()
                    )
                    .build()
            )

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .addContent(trackArc.build())
            .addContent(sliderArc.build())
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
            val squareH = if (isHeaderFooter) 10f else 0f
            return ModifiersBuilders.Padding.Builder()
                .setTop(DimensionBuilders.dp(top))
                .setBottom(DimensionBuilders.dp(bottom))
                .setStart(DimensionBuilders.dp(start + squareH))
                .setEnd(DimensionBuilders.dp(end + squareH))
                .build()
        }

        val h = if (isHeaderFooter) 34f else 16f
        val t = if (isHeaderFooter && top <= 2f) 16f else if (isHeaderFooter) top + 4f else top
        val b = if (isHeaderFooter) (if (bottom > 0f) bottom else 12f) else bottom

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

    private fun timeText(time: String, color: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(strProp(time))
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setColor(ColorBuilders.argb(color))
                    .setSize(DimensionBuilders.sp(12f))
                    .setWeight(boldWeight())
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

    private fun mediumWeight(): LayoutElementBuilders.FontWeightProp =
        LayoutElementBuilders.FontWeightProp.Builder()
            .setValue(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
            .build()
}

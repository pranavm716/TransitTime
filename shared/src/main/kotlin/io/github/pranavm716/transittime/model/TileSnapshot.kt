package io.github.pranavm716.transittime.model

import io.github.pranavm716.transittime.data.model.Agency

enum class IconShape { SQUARE, CIRCLE, ROUNDED_RECT, RECT }

data class TileSnapshot(
    val stopId: String,
    val stopName: String,
    val agency: Agency,
    val fetchedAt: Long,
    val errorLabel: String?,
    val goModeActive: Boolean,
    val goModeExpiresAt: Long,
    val rows: List<TileRow>
)

data class TileRow(
    val routeName: String,
    val headsign: String,
    val displayTimes: List<String>,
    val delayColors: List<Int>,
    // Nullable so old Gson-cached snapshots without these fields deserialize safely.
    val iconText: String?,
    val iconBgColor: Int,
    val iconTextColor: Int,
    val iconShape: IconShape?
)

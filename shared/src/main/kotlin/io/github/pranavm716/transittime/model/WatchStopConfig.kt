package io.github.pranavm716.transittime.model

import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.DelayColorMode

data class WatchStopConfig(
    val stopId: String,
    val stopName: String,
    val agency: Agency,
    val delayColorMode: DelayColorMode
)

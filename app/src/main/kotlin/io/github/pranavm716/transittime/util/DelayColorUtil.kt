package io.github.pranavm716.transittime.util

import io.github.pranavm716.transittime.data.model.DelayColorMode
import kotlin.math.roundToInt

val COLOR_ON_TIME = 0xFFFFC107.toInt()
val COLOR_LATE = 0xFFdc3545.toInt()
val COLOR_EARLY = 0xFF28a745.toInt()

private fun lerp(from: Int, to: Int, t: Float): Int {
    val r = ((from shr 16 and 0xFF) + ((to shr 16 and 0xFF) - (from shr 16 and 0xFF)) * t).roundToInt()
    val g = ((from shr 8 and 0xFF) + ((to shr 8 and 0xFF) - (from shr 8 and 0xFF)) * t).roundToInt()
    val b = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * t).roundToInt()
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
}

private fun dimmed(color: Int): Int {
    val dim = 0.62f
    val r = ((color shr 16 and 0xFF) * dim).roundToInt()
    val g = ((color shr 8 and 0xFF) * dim).roundToInt()
    val b = ((color and 0xFF) * dim).roundToInt()
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
}

fun getDelayColor(delaySeconds: Int?, isScheduled: Boolean, delayColorMode: DelayColorMode): Int {
    if (delayColorMode == DelayColorMode.NONE) {
        return if (isScheduled) dimmed(COLOR_ON_TIME) else COLOR_ON_TIME
    }

    if (isScheduled) return dimmed(COLOR_ON_TIME)

    if (delaySeconds == null || delaySeconds in -60..60) return COLOR_ON_TIME

    if (delayColorMode == DelayColorMode.FLAT) {
        return if (delaySeconds > 60) COLOR_LATE else COLOR_EARLY
    }

    // GRADIENT
    return if (delaySeconds > 60) {
        val t = ((delaySeconds - 60).toFloat() / (300 - 60)).coerceIn(0f, 1f)
        lerp(COLOR_ON_TIME, COLOR_LATE, t)
    } else {
        val t = ((-delaySeconds - 60).toFloat() / (180 - 60)).coerceIn(0f, 1f)
        lerp(COLOR_ON_TIME, COLOR_EARLY, t)
    }
}

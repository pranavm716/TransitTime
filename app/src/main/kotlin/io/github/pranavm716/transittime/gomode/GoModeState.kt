package io.github.pranavm716.transittime.gomode

sealed class GoModeState {
    object Inactive : GoModeState()
    data class Active(val widgetId: Int, val expiresAt: Long) : GoModeState()
}

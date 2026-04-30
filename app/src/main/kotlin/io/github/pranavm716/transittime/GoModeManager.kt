package io.github.pranavm716.transittime

import android.content.Context
import androidx.core.content.edit

class GoModeManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var goModeExpiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_EXPIRES_AT, value) }

    val isGoModeActive: Boolean
        get() = goModeExpiresAt > System.currentTimeMillis()

    companion object {
        const val GO_MODE_DURATION_MS: Long = 15 * 60 * 1000L
        private const val PREFS_NAME = "transit_go_mode_prefs"
        private const val KEY_EXPIRES_AT = "go_mode_expires_at"
    }
}

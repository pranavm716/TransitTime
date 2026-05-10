package io.github.pranavm716.transittime

import android.content.Context
import io.github.pranavm716.transittime.model.RefreshState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton to manage the global refresh state of the application.
 * Coordinates between FetchWorker and UI components (Widgets/Tiles).
 */
class RefreshManager private constructor(context: Context) {
    private val appContext = context.applicationContext

    private val _refreshState = MutableStateFlow(RefreshState.IDLE)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    fun updateState(newState: RefreshState) {
        if (_refreshState.value == newState) return
        _refreshState.value = newState
    }

    companion object {
        @Volatile
        private var INSTANCE: RefreshManager? = null

        fun getInstance(context: Context): RefreshManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RefreshManager(context).also { INSTANCE = it }
            }
        }
    }
}

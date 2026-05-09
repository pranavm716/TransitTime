package io.github.pranavm716.transittime.model

/**
 * Represents the lifecycle states of a refresh operation.
 *
 * 1. IDLE: No refresh is occurring. No animations.
 * 2. INITIATED: A refresh has been requested. Animations start if a network call is intended.
 * 3. FETCHING: Network call is in progress. Animations active.
 * 4. RENDERING: Data has been received. Animations finish their current cycle before transitioning to IDLE.
 */
enum class RefreshState {
    IDLE,
    INITIATED,
    FETCHING,
    RENDERING
}

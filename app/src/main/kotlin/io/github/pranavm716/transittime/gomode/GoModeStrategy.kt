package io.github.pranavm716.transittime.gomode

import android.content.Context
import android.view.View
import io.github.pranavm716.transittime.R

interface GoModeStrategy {
    val isGoModeActive: Boolean
    val dotVisibility: Int
    val refreshIconVisibility: Int
    fun getFreshnessColor(context: Context, hasError: Boolean): Int
    fun triggerAnimation(context: Context, widgetId: Int)
}

class InactiveStrategy : GoModeStrategy {
    override val isGoModeActive = false
    override val dotVisibility = View.GONE
    override val refreshIconVisibility = View.VISIBLE

    override fun getFreshnessColor(context: Context, hasError: Boolean): Int {
        return if (hasError) {
            0xFFdc3545.toInt() // COLOR_LATE
        } else {
            context.getColor(R.color.widget_color_secondary)
        }
    }

    override fun triggerAnimation(context: Context, widgetId: Int) {
        // No animation for inactive
    }
}

class ActiveStrategy : GoModeStrategy {
    override val isGoModeActive = true
    override val dotVisibility = View.VISIBLE
    override val refreshIconVisibility = View.GONE

    override fun getFreshnessColor(context: Context, hasError: Boolean): Int {
        return if (hasError) {
            0xFFdc3545.toInt() // COLOR_LATE
        } else {
            context.getColor(R.color.accent_color)
        }
    }

    override fun triggerAnimation(context: Context, widgetId: Int) {
        // Pulse animation will be handled by FetchWorker/Widget
    }
}

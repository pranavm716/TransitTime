package io.github.pranavm716.transittime.gomode

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.widget.TransitWidget

interface GoModeStrategy {
    val isGoModeActive: Boolean
    val dotVisibility: Int
    val refreshIconVisibility: Int
    fun getFreshnessColor(context: Context, hasError: Boolean): Int
    fun startAnimation(context: Context, manager: AppWidgetManager, widgetId: Int)
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

    override fun startAnimation(context: Context, manager: AppWidgetManager, widgetId: Int) {
        TransitWidget.animateRefreshIcon(context, manager, widgetId)
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

    override fun startAnimation(context: Context, manager: AppWidgetManager, widgetId: Int) {
        TransitWidget.animateGoModeDot(context, manager, widgetId)
    }
}

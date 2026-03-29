package io.github.pranavm716.transittime.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ListView

class NonScrollableListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ListView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasureSpecCustom = View.MeasureSpec.makeMeasureSpec(
            Int.MAX_VALUE shr 2, View.MeasureSpec.AT_MOST
        )
        super.onMeasure(widthMeasureSpec, heightMeasureSpecCustom)
        val params = layoutParams
        params.height = measuredHeight
    }
}

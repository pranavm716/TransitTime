package io.github.pranavm716.transittime.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class DelayGradientBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaledDensity

    private val barH = dp(8f)
    private val cornerR = dp(4f)
    private val triW = dp(8f)
    private val triH = dp(6f)
    private val triGap = dp(3f)
    private val labelGap = dp(3f)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt()
        textSize = sp(10f)
    }

    private val triPath = Path()

    private val labels = listOf("Early", "On time", "Late")

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = labelPaint.fontMetrics
        val h = barH + triGap + triH + labelGap + (fm.descent - fm.ascent)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val halfTriW = triW / 2f

        barPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(TransitWidget.COLOR_EARLY, TransitWidget.COLOR_ON_TIME, TransitWidget.COLOR_LATE),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(0f, 0f, w, barH), cornerR, cornerR, barPaint)

        val tipY = barH + triGap
        val baseY = tipY + triH
        val fm = labelPaint.fontMetrics
        val labelBaselineY = baseY + labelGap - fm.ascent

        val leftX = maxOf(halfTriW, labelPaint.measureText(labels[0]) / 2f)
        val rightX = w - maxOf(halfTriW, labelPaint.measureText(labels[2]) / 2f)
        val positions = listOf(leftX, w / 2f, rightX)
        labelPaint.textAlign = Paint.Align.CENTER

        for (i in 0..2) {
            val cx = positions[i]
            triPath.reset()
            triPath.moveTo(cx, tipY)
            triPath.lineTo(cx - halfTriW, baseY)
            triPath.lineTo(cx + halfTriW, baseY)
            triPath.close()
            canvas.drawPath(triPath, triPaint)

            canvas.drawText(labels[i], cx, labelBaselineY, labelPaint)
        }
    }
}

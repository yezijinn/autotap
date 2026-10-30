package com.auto.fairy.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.auto.fairy.R

/**
 * 步骤序号字体预览：按当前参数画「1 2 3」，让设置页的修改即时可见。
 *
 * 与 [CoordinateMarkers] 共用同一套绘制逻辑（数字只填充），
 * 参数来自 [OrdinalStyle]，改完马上重绘。
 */
class OrdinalStylePreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        color = ContextCompat.getColor(context, R.color.status_error)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var style: OrdinalStyle = OrdinalStyle.DEFAULT
    private var gapPx = OrdinalStyle.DEFAULT_GAP_DP * density

    /** 参数变更后调用：立即按新参数重绘。 */
    fun setStyle(value: OrdinalStyle) {
        style = value
        gapPx = value.gapDp * density
        fillPaint.color = value.color
        fillPaint.textSize = value.sizeSp * density
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = 5f * density
        val gap = 56f * density
        val cy = height / 2f
        val total = gap * 2
        val startX = (width - total) / 2f + gap / 2f
        for (i in 0..2) {
            val cx = startX + i * gap
            canvas.drawLine(cx - half, cy, cx + half, cy, crossPaint)
            canvas.drawLine(cx, cy - half, cx, cy + half, crossPaint)
            val label = (i + 1).toString()
            val metrics = fillPaint.fontMetrics
            val baseline = cy - (metrics.ascent + metrics.descent) / 2f
            val textX = cx + half + gapPx + fillPaint.measureText(label) / 2f
            canvas.drawText(label, textX, baseline, fillPaint)
        }
    }
}

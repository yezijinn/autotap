package com.auto.fairy.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.auto.fairy.R

/**
 * 取坐标准星：贯穿全屏的十字线 + 外环 + 中心点。
 *
 * M4 不做位图放大（浮层拿不到底层画面），用半透明遮罩 + 高对比准星保证任何背景下可读。
 */
class CoordinateCrosshair @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var lastX = -1f
    private var lastY = -1f

    private val density = resources.displayMetrics.density
    private val ringRadius = resources.getDimension(R.dimen.picker_ring_radius)

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }

    fun setPoint(x: Float, y: Float) {
        lastX = x
        lastY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lastX < 0f || lastY < 0f) return
        // 十字线贯穿全屏，方便对齐屏幕边缘与其它控件
        canvas.drawLine(0f, lastY, width.toFloat(), lastY, crossPaint)
        canvas.drawLine(lastX, 0f, lastX, height.toFloat(), crossPaint)
        canvas.drawCircle(lastX, lastY, ringRadius, ringPaint)
        canvas.drawCircle(lastX, lastY, 4f * density, dotPaint)
    }
}

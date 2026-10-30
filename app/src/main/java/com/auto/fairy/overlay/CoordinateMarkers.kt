package com.auto.fairy.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.auto.fairy.R
import com.auto.fairy.core.model.Script
import java.util.Locale

/**
 * 坐标标记层：全屏绘制脚本里每个关键点（点击 / 滑动 / 拖动热点的药丸）。
 *
 * 标记形状为红色十字：水平与垂直线段相交。基础总长 10dp、线宽 2dp；
 * 该步正在执行时两端各加长 4dp（总长 18dp）、线宽 +2dp（4dp），执行过后恢复。
 * 步骤数字颜色始终静态，不随执行变色。
 *
 * 绘制层本身只读（FLAG_NOT_TOUCHABLE）；拖动靠每个圆点单独的小触摸窗，
 * 见 [CoordinateMarkersController]；拖动中只走 [setDragPreview] 的轻量重绘。
 */
class CoordinateMarkers @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 摊平后的标记（[FlatMark]），[ordinal] 与播放的步序号同口径，用于高亮当前步。 */
    private var entries: List<FlatMark> = emptyList()

    /** [entries] 由摊平后的源脚本生成，同实例不变则同数据，不必重算。 */
    private var entriesScript: Script? = null
    private var screenW = 0
    private var screenH = 0

    /** 正在执行的步骤序号，0 表示不在执行，此时无十字放大。 */
    private var activeOrdinal = 0

    /** 正在拖动的标记下标；-1 表示没有。 */
    private var dragIndex = -1
    private var dragX = 0f
    private var dragY = 0f

    private val density = resources.displayMetrics.density
    private val hitPx = resources.getDimension(R.dimen.marker_hit)
    private val labelPx = resources.getDimension(R.dimen.marker_label)

    private val redColor = ContextCompat.getColor(context, R.color.status_error)

    /** 数字与十字的左右间距（px），由用户设置。 */
    private var labelGapPx = OrdinalStyle.DEFAULT_GAP_DP * density

    // 十字几何：基础总长 10dp（半长 5dp）、线宽 2dp；执行中半长 9dp（总长 18dp）、线宽 4dp。
    private val crossHalfPx = 5f * density
    private val crossHalfActivePx = 9f * density
    private val crossThickPx = 2f * density
    private val crossThickActivePx = 4f * density
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = redColor
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OrdinalStyle.DEFAULT.color
        textAlign = Paint.Align.CENTER
        textSize = OrdinalStyle.DEFAULT.sizeSp * density
        isFakeBoldText = true
    }

    /** 滑动起点数字「加粗 10%」用：同色描边叠在填充下方。 */
    private val labelBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE61F2937.toInt()
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = labelPx
        isFakeBoldText = true
    }
    private val pillRect = RectF()

    /** 复用数组：onDraw 里取本视图在屏幕上的位置，用于抵消窗口内边距。 */
    private val screenLoc = IntArray(2)

    /** 命中半径（px）：触摸窗边长按此摆位。 */
    fun touchSpan(): Int = (hitPx * 2f).toInt().coerceAtLeast(24)

    /**
     * 实际尺寸改变（旋转 / 分屏 / 系统变化）后回调，用于重建归一化映射与触摸窗，
     * 否则下一轮旋转后圆点会按旧分辨率画在错误位置。
     */
    var onViewSizeChanged: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (w != screenW || h != screenH) onViewSizeChanged?.invoke()
    }

    fun bind(script: Script?, screenW: Int, screenH: Int) {
        this.screenW = screenW.coerceAtLeast(1)
        this.screenH = screenH.coerceAtLeast(1)
        dragIndex = -1
        // 同一步脚本用同一实例判断，避免 data class 逐字段比较整份 steps
        if (script !== entriesScript) {
            entriesScript = script
            entries = script?.let { MarkerFlatten.of(it.steps) } ?: emptyList()
        }
        invalidate()
    }

    fun markerCount(): Int = entries.size

    fun entryAt(index: Int): FlatMark? = entries.getOrNull(index)

    /** 当前正在执行的步骤序号，0 = 不在执行。 */
    fun setActiveOrdinal(ordinal: Int) {
        if (activeOrdinal == ordinal) return
        activeOrdinal = ordinal
        invalidate()
    }

    /**
     * 应用用户自定义的步骤序号字体（设置页与坐标层共用）。
     *
     * 只改 Paint，不触碰布局：十字位置与偏移都与字号无关，改字号无需重建触摸窗。
     */
    fun setOrdinalStyle(value: OrdinalStyle) {
        labelGapPx = value.gapDp * density
        labelPaint.color = value.color
        labelPaint.textSize = value.sizeSp * density
        invalidate()
    }

    /** 拖动中的实时位置（重绘），由触摸窗回调来，只重绘不重建。 */
    fun setDragPreview(index: Int, x: Float, y: Float) {
        dragIndex = index
        dragX = x.coerceIn(0f, screenW.toFloat())
        dragY = y.coerceIn(0f, screenH.toFloat())
        invalidate()
    }

    fun clearDragPreview() {
        if (dragIndex < 0) return
        dragIndex = -1
        invalidate()
    }

    // ── 绘制 ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty() || screenW <= 0 || screenH <= 0) return

        // 绘制层窗口可能整体被下移（如状态栏内边距），而触摸热区用的是绝对屏幕坐标。
        // 若直接按「点×屏高」在本地画，十字会比热区低一个内边距——手指按十字正好落空、
        // 穿透到下层（真机复现的「拖不动」）。这里减去本视图在屏幕上的偏移，让十字
        // 画在绝对屏幕坐标处，和热区严格重合。
        getLocationOnScreen(screenLoc)
        val ox = screenLoc[0].toFloat()
        val oy = screenLoc[1].toFloat()

        entries.forEachIndexed { index, entry ->
            val dragging = index == dragIndex
            val ax = if (dragging) dragX else entry.point.x * screenW
            val ay = if (dragging) dragY else entry.point.y * screenH
            val cx = ax - ox
            val cy = ay - oy
            val active = !dragging && activeOrdinal > 0 && entry.ordinal == activeOrdinal
            drawCross(canvas, cx, cy, active)
            // 滑动起点（Swipe 的第 0 点）数字加粗 10%，与终点区分
            val startBold = entry.path is MarkerPath.Swipe && entry.path.point == 0
            drawLabel(canvas, cx, cy, crossHalfPx, entry.label, startBold)
            if (dragging) drawBubble(canvas, cx, cy, crossHalfPx)
        }
    }

    /** 画红色十字：执行中只把左、上、下三端加长，右端保持基础长度。 */
    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, active: Boolean) {
        if (active) {
            crossPaint.strokeWidth = crossThickActivePx
            canvas.drawLine(cx - crossHalfActivePx, cy, cx + crossHalfPx, cy, crossPaint)
            canvas.drawLine(cx, cy - crossHalfActivePx, cx, cy + crossHalfActivePx, crossPaint)
        } else {
            crossPaint.strokeWidth = crossThickPx
            canvas.drawLine(cx - crossHalfPx, cy, cx + crossHalfPx, cy, crossPaint)
            canvas.drawLine(cx, cy - crossHalfPx, cx, cy + crossHalfPx, crossPaint)
        }
    }

    /**
     * 数字放在十字右侧（贴边时翻到左侧）。颜色始终取用户设置色，执行中也不变色。
     * [bold] 为真时（滑动起点）同色描边叠一层，字重约加粗 10%。
     */
    private fun drawLabel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        half: Float,
        label: String,
        bold: Boolean,
    ) {
        val textWidth = labelPaint.measureText(label)
        val gap = labelGapPx
        var x = cx + half + gap + textWidth / 2f
        if (x + textWidth / 2f > width) x = cx - half - gap - textWidth / 2f
        x = x.coerceIn(
            textWidth / 2f,
            (width - textWidth / 2f).coerceAtLeast(textWidth / 2f),
        )
        val baseline = cy - (labelPaint.ascent() + labelPaint.descent()) / 2f
        if (bold) {
            labelBoldPaint.color = labelPaint.color
            labelBoldPaint.textSize = labelPaint.textSize
            labelBoldPaint.strokeWidth = labelPaint.textSize * 0.10f
            canvas.drawText(label, x, baseline, labelBoldPaint)
        }
        canvas.drawText(label, x, baseline, labelPaint)
    }

    /** 拖动中的实时坐标气泡。 */
    private fun drawBubble(canvas: Canvas, cx: Float, cy: Float, half: Float) {
        val text = String.format(Locale.US, "(%d,%d)", dragX.toInt(), dragY.toInt())
        val textWidth = bubbleTextPaint.measureText(text)
        val bubbleW = textWidth + 10f * density
        val bubbleH = labelPx + 7f * density
        var left = cx - bubbleW / 2f
        left = left.coerceIn(0f, (width - bubbleW).coerceAtLeast(0f))
        var top = cy - half - 4f * density - bubbleH
        if (top < 0f) top = cy + half + 4f * density
        pillRect.set(left, top, left + bubbleW, top + bubbleH)
        val radius = 4f * density
        canvas.drawRoundRect(pillRect, radius, radius, bubblePaint)

        val baseline = top + bubbleH / 2f - (bubbleTextPaint.ascent() + bubbleTextPaint.descent()) / 2f
        canvas.drawText(text, left + bubbleW / 2f, baseline, bubbleTextPaint)
    }
}

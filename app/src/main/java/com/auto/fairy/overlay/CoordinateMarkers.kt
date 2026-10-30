package com.auto.fairy.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import com.auto.fairy.R
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import java.util.Locale
import kotlin.math.atan2

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

    /** 原始脚本（未摊平）：用于按 ordinal 反查 Step，拿到滑动的起终点与 durationMs。 */
    private var sourceScript: Script? = null
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

    // ── 滑动轨迹与箭头 ────────────────────────────────────────
    // 需求：滑动步骤的起点↔终点画一条 1px 紫色细线；播放到该步骤时，
    // 一个从起点指向终点的箭头沿线段匀速走完全程（速度由 durationMs 决定），到终点消失。

    /**
     * 1px = 物理像素（device pixel）。理由与校验：
     * 1. 本 View 的 [onDraw] 画布坐标用的是**设备像素**（证据：十字线宽写的是
     *    `5f * density`，即「dp→设备像素」是乘 density，反推画布单位是设备像素而非 dp）。
     * 2. 需求要「确确实实一根 1px 细线」，故线宽直接 = 1（一个设备像素）。
     *    这天然是物理像素，且在任意 density（含 560dpi）下都恰为 1 个真实像素——
     *    既满足「1px」字面，又不会因密度升高而变成看不见的亚像素线。
     * 3. 反例：若写成 `1 / density`，在 3× 密度屏上会变成 1/3 物理像素的**亚像素线**，
     *    被抗锯齿摊薄到几乎不可见，那才是 560dpi 下「看不见」的真正原因；本实现不取它。
     * 4. 箭头尺寸（~10dp）另按视觉需要取 dp（乘 density），与「1px 线」是两个独立决策：
     *    线要极细、箭头要可见，互不冲突。
     */
    private val lineWidthPx = 1f

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        // 紫色：#A020F0（纯绘制，不改任何注入时序、不新增触摸窗）
        color = 0xFFA020F0.toInt()
        strokeWidth = lineWidthPx
    }

    /** 箭头填充（与连线同色）。 */
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFA020F0.toInt()
    }

    /** 复用箭头三角路径，避免每帧 new（性能约束：不得每帧分配大对象）。 */
    private val arrowPath = Path()

    /**
     * 持久滑动线段（归一化坐标）：每个滑动步骤一条起↔终点连线，始终绘制，
     * 与播放无关（点击/等待/循环不产生）。由 [bind] 从源脚本一次性算出。
     */
    private var swipeSegs: List<SwipeSeg> = emptyList()

    /**
     * 当前正在播放的滑动步的屏幕几何 + 时间参数（已扣窗口偏移）。
     * 只存几何与时间长度，进度不缓存——每帧由播放时钟现算，因此跨轮 / 跨遍自动复位，
     * 不会越界残留，也不依赖「序号变化」事件来重启（解决单步循环时序号不变、箭头不再出现）。
     *
     * [preMs] 是该步的「动作延迟」：滑动的真实手指运动发生在
     * `[窗口起点 + preMs, 窗口起点 + preMs + durationMs]`
     * （见 [com.auto.fairy.core.compiler.ScriptCompiler] 与 [PlaybackController.indexSteps]）。
     * 箭头据此在 preMs 之前停在起点、之后按 durationMs 匀速走到终点，与真实滑动严格对齐——
     * 否则箭头会在 preMs 的死区里提前走动、且用 `preMs+durationMs` 的真实时长去除以 durationMs，
     * 速度算错（默认 preMs=1000ms 时尤其明显）。
     */
    private data class ArrowGeom(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val preMs: Long,
        val durationMs: Long,
    )

    /**
     * 当前正在动画的滑动步序号；0 = 无箭头、-1 = 强制下一帧重算（旋转 / 重绑后屏幕尺寸变了）。
     * 与 [arrowGeom] 配套：序号变化时（含跨轮循环序号回绕、步骤切换）才重算几何，避免每帧搜索。
     */
    private var arrowOrdinal = 0
    private var arrowGeom: ArrowGeom? = null

    /** 本帧箭头的绘制态（位置 / 朝向），由帧回调算出、[onDraw] 直接消费，避免重复计算。 */
    private var arrowActive = false
    private var arrowX = 0f
    private var arrowY = 0f
    private var arrowAngle = 0f

    private val choreographer = Choreographer.getInstance()
    /** 帧回调是否已排进 Choreographer 但尚未执行：避免同一回调被排两次（会翻倍执行）。 */
    private var arrowPending = false
    private val arrowFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            arrowPending = false
            // 非 RUNNING（暂停 / 停止 / 退出）一律清箭头：暂停即停、停止立刻消失，满足需求硬要求。
            if (!com.auto.fairy.PlaybackController.isPlaying()) {
                clearArrow()
                return
            }
            val ord = com.auto.fairy.PlaybackController.cursorStepIndex()
            // 序号变了（或强制重算）才重算几何：跨轮循环序号回绕、步骤切换都能自动跟上。
            if (ord != arrowOrdinal) loadArrowGeom(ord)
            val g = arrowGeom
            if (g == null) {
                // 当前步不是滑动：不画，但继续轮询（下一步 / 下一轮可能又是滑动）。
                arrowActive = false
            } else {
                // 进度每帧现算：窗口起点 + 动作延迟才是真实滑动起点；速度由 durationMs 决定。
                val windowStart = com.auto.fairy.PlaybackController.stepOffsetMs(ord) + g.preMs
                val dur = g.durationMs
                val elapsed = com.auto.fairy.PlaybackController.cursorElapsedMs()
                val p = if (dur <= 0L) 1f else ((elapsed - windowStart).toFloat() / dur).coerceIn(0f, 1f)
                if (p < 1f) {
                    // 在 [0,1) 内才画：preMs 之前 p<0 被 clamp 到 0（箭头停在起点等待）；
                    // 走过终点 p>=1 不画，但继续轮询——下一轮 cursorElapsedMs 回绕后自动重画。
                    val dx = g.x1 - g.x0
                    val dy = g.y1 - g.y0
                    arrowX = g.x0 + dx * p
                    arrowY = g.y0 + dy * p
                    arrowAngle = if (dx == 0f && dy == 0f) 0f
                    else Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    arrowActive = true
                } else {
                    arrowActive = false
                }
            }
            // 每帧都重绘一次（即便本帧不画箭头，也要把上一帧的箭头清掉）；进度 / 几何变化都靠下一帧续排。
            invalidate()
            scheduleArrowFrame()
        }
    }

    /** 起 / 续帧回调：已排未执行则不重复排（Choreographer 同回调排两次会翻倍执行）。 */
    private fun scheduleArrowFrame() {
        if (arrowPending) return
        arrowPending = true
        choreographer.postFrameCallback(arrowFrame)
    }

    /** 清掉箭头、停帧回调：线箭头一并消失（不残留）。 */
    private fun clearArrow() {
        arrowActive = false
        arrowOrdinal = 0
        arrowGeom = null
        if (arrowPending) {
            arrowPending = false
            choreographer.removeFrameCallback(arrowFrame)
        }
        invalidate()
    }

    /** 一条常驻滑动线段的归一化起终点。 */
    private data class SwipeSeg(val sx: Float, val sy: Float, val ex: Float, val ey: Float)

    /** 由源脚本收集所有滑动的起↔终点（归一化），供常驻紫色细线绘制。 */
    private fun collectSwipeSegments(script: Script): List<SwipeSeg> {
        val out = ArrayList<SwipeSeg>()
        fun walk(steps: List<Step>) {
            for (step in steps) {
                if (step is Step.Swipe && step.points.size >= 2) {
                    val s0 = step.points.first()
                    val s1 = step.points.last()
                    out.add(SwipeSeg(s0.x, s0.y, s1.x, s1.y))
                } else if (step is Step.Loop) {
                    walk(step.body)
                }
            }
        }
        walk(script.steps)
        return out
    }

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
            sourceScript = script
            entries = script?.let { MarkerFlatten.of(it.steps) } ?: emptyList()
            swipeSegs = script?.let { collectSwipeSegments(it) } ?: emptyList()
        }
        // 屏幕 / 脚本变化：下一帧重算箭头几何（分辨率或起点终点可能已变）。
        arrowOrdinal = -1
        arrowActive = false
        invalidate()
    }

    /** 视图被窗口摘除（控制器 [hide] 卸载层）时停掉箭头帧回调，避免轮询已 detach 的视图。 */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearArrow()
    }

    fun markerCount(): Int = entries.size

    fun entryAt(index: Int): FlatMark? = entries.getOrNull(index)

    /**
     * 当前正在执行的步骤序号，0 = 不在执行（IDLE 或 PAUSED 都归零，由控制器决定）。
     *
     * 行为：
     * - 任何「清零」（IDLE/PAUSED 都传 0）→ 立刻清箭头（暂停/停止立刻消失，不残留）；
     * - 进入某个步骤 → 标记序号，由跟随播放时钟的帧回调现算几何/进度并起动画。
     *
     * 注意不走 `prev == ordinal` 的早退：单步循环（[ordinal] 始终不变）需要靠跨轮回绕后
     * 的帧里 [cursorElapsedMs] 归零来重新画箭头，而不是靠序号变化；这里只切换序号，
     * 真正的「重画 / 复位」交给帧回调对播放时钟的每帧读取。
     */
    fun setActiveOrdinal(ordinal: Int) {
        activeOrdinal = ordinal
        if (ordinal <= 0) {
            // IDLE / PAUSED：箭头立刻消失。
            clearArrow()
        } else {
            // 只切序号、不在这里预置 arrowOrdinal：几何由帧回调按 arrowOrdinal 变化重算，
            // 预置会让 `ord == arrowOrdinal` 成立而永远不加载几何（实测单步滑动无箭头）。
            scheduleArrowFrame()
        }
        invalidate()
    }

    /**
     * 播放推进到 [ordinal] 步且该步是滑动时：按屏幕物理像素算出起终点 + 动作延迟 + 时长，
     * 供帧回调画箭头。
     *
     * 几何用归一化坐标 × 屏幕尺寸 − 窗口偏移得到，与十字 / 触摸热区严格重合。
     * 取不到或不是滑动就清几何：保证「点击 / 等待 / 循环」绝不多画线或箭头。
     */
    private fun loadArrowGeom(ordinal: Int) {
        arrowOrdinal = ordinal
        val script = sourceScript
        val step = script?.let { findStepByOrdinal(it.steps, ordinal) }
        if (step !is Step.Swipe || step.points.size < 2) {
            arrowGeom = null
            return
        }
        val s0 = step.points.first()
        val s1 = step.points.last()
        getLocationOnScreen(screenLoc)
        val ox = screenLoc[0].toFloat()
        val oy = screenLoc[1].toFloat()
        val x0 = s0.x * screenW - ox
        val y0 = s0.y * screenH - oy
        val x1 = s1.x * screenW - ox
        val y1 = s1.y * screenH - oy
        arrowGeom = ArrowGeom(
            x0, y0, x1, y1,
            preMs = Step.effectivePreDelay(step.preDelayMs, script.defaultActionDelayMs),
            durationMs = step.durationMs,
        )
    }

    /**
     * 深度优先按展开序号定位 Step（与 [MarkerFlatten] 同口径：顶层 1..N，循环体「父.子」）。
     * 找不到返回 null（如序号落在不可达的无限循环之后）。
     */
    private fun findStepByOrdinal(steps: List<Step>, target: Int): Step? {
        var counter = 0
        fun walk(list: List<Step>): Step? {
            for (step in list) {
                counter++
                if (counter == target) return step
                if (step is Step.Loop) {
                    walk(step.body)?.let { return it }
                    // 无限循环之后整条不可达，停止向下找
                    if (step.times == 0) return null
                }
            }
            return null
        }
        return walk(steps)
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

    /**
     * 停掉箭头动画：播放停止/退出时调用，确保箭头不残留。
     * 常驻紫色细线（[swipeSegs]）保留——那是与播放无关的静态可视化。
     *
     * 暂停（PAUSED）时控制器传 ordinal=0 → [setActiveOrdinal] 已清；
     * 此处作为 IDLE/退出前的兜底再清一次，满足「暂停/停止立刻消失」。
     */
    fun stopSwipeArrow() {
        clearArrow()
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

        // 常驻紫色细线：每个滑动步骤的起↔终点连线（deliverable 1，与播放无关）。
        for (seg in swipeSegs) {
            drawTrailLine(canvas, seg.sx * screenW - ox, seg.sy * screenH - oy, seg.ex * screenW - ox, seg.ey * screenH - oy)
        }

        // 播放中滑动步骤的箭头：沿当前轨迹从起点匀速移动到终点，到终点消失。
        if (arrowActive) drawArrow(canvas)

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

    /** 画常驻的 1px 紫色滑动连线（物理像素宽）。 */
    private fun drawTrailLine(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        trailPaint.strokeWidth = lineWidthPx
        canvas.drawLine(x0, y0, x1, y1, trailPaint)
    }

    /**
     * 画沿轨迹移动的箭头：位置/朝向由帧回调算好存进 [arrowX]/[arrowY]/[arrowAngle]，
     * 这里只负责绘制。进度由播放时钟每帧现算（速度由 [ArrowGeom.durationMs] 决定），
     * 从起点出发、匀速走到终点、到终点即停在 [0,1) 之外不再绘制 → 消失、不残留。
     */
    private fun drawArrow(canvas: Canvas) {
        val size = 10f * density
        arrowPath.rewind()
        arrowPath.moveTo(arrowX + size, arrowY)
        arrowPath.lineTo(arrowX - size * 0.6f, arrowY - size * 0.6f)
        arrowPath.lineTo(arrowX - size * 0.6f, arrowY + size * 0.6f)
        arrowPath.close()
        canvas.save()
        canvas.rotate(arrowAngle, arrowX, arrowY)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
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

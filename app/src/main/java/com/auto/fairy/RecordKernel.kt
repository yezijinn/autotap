package com.auto.fairy

import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Step
import kotlin.math.hypot

/**
 * 录制内核：把原始 MotionEvent 序列翻译成语义步骤。
 *
 * 判定规则（设计方案 3.7）：
 * - 按下→抬起 < 150ms 且位移 < 8dp → [Step.Tap]
 * - 单指移动 → [Step.Swipe]，路径用 RDP（容差 1px）简化
 * - ≥2 指 → TODO(M2)：多指录制暂不采集，避免产出语义错误的数据；数据模型已支持 Multi
 *
 * 只在 ACTION_UP 时产生一个步骤，坐标统一换算成 [0,1]。
 *
 * 参数只收「x / y / 事件时刻 / 手指数」四样，不绑 MotionEvent：取值归调用方
 * （[RecordOverlay]），这里保持纯计算，判定规则才进得了 JVM 单测。
 */
class RecordKernel(
    private val screenWidth: Int,
    private val screenHeight: Int,
    density: Float,
) {

    private class Px(val x: Float, val y: Float)

    private val tapSlopPx = 8f * density
    private val path = ArrayList<Px>(64)
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false
    private var active = false

    fun onDown(x: Float, y: Float, timeMs: Long, pointerCount: Int) {
        active = true
        multiTouch = pointerCount > 1
        downTime = timeMs
        downX = x
        downY = y
        path.clear()
        path.add(Px(downX, downY))
    }

    fun onPointerDown(pointerCount: Int) {
        if (pointerCount > 1) multiTouch = true
    }

    fun onMove(x: Float, y: Float, pointerCount: Int) {
        if (!active || multiTouch) return
        if (pointerCount > 1) {
            multiTouch = true
            return
        }
        val last = path.lastOrNull()
        if (last != null && hypot(x - last.x, y - last.y) < 1f) return
        path.add(Px(x, y))
    }

    /** 抬起；返回 null 表示本次手势被丢弃（多指 / 异常）。 */
    fun onUp(x: Float, y: Float, timeMs: Long): Step? {
        if (!active) return null
        active = false
        if (multiTouch || path.isEmpty()) {
            path.clear()
            return null
        }
        val upX = x.coerceIn(0f, screenWidth.toFloat())
        val upY = y.coerceIn(0f, screenHeight.toFloat())
        val last = path.last()
        if (last.x != upX || last.y != upY) {
            path.add(Px(upX, upY))
        }
        val duration = (timeMs - downTime).coerceAtLeast(0L)
        val moved = hypot(upX - downX, upY - downY)

        val step = if (duration < TAP_MAX_MS && moved < tapSlopPx) {
            Step.Tap(
                points = listOf(normalize(downX, downY)),
                durationMs = duration.coerceAtLeast(TAP_MIN_MS),
            )
        } else {
            val simplified = simplify(path, RDP_TOLERANCE_PX)
            Step.Swipe(
                points = simplified.map { normalize(it.x, it.y) },
                durationMs = duration.coerceAtLeast(SWIPE_MIN_MS),
            )
        }
        path.clear()
        return step
    }

    fun onCancel() {
        active = false
        multiTouch = false
        path.clear()
    }

    private fun normalize(x: Float, y: Float): Point = Point(
        x = (x / screenWidth.toFloat()).coerceIn(0f, 1f),
        y = (y / screenHeight.toFloat()).coerceIn(0f, 1f),
    )

    // ── Ramer-Douglas-Peucker ───────────────────────────────

    private fun simplify(points: List<Px>, epsilon: Float): List<Px> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        simplifyRange(points, 0, points.size - 1, epsilon, keep)
        val result = ArrayList<Px>(points.size)
        for (i in points.indices) if (keep[i]) result.add(points[i])
        return result
    }

    private fun simplifyRange(
        points: List<Px>,
        start: Int,
        end: Int,
        epsilon: Float,
        keep: BooleanArray,
    ) {
        if (end <= start + 1) return
        val a = points[start]
        val b = points[end]
        var maxDistance = -1f
        var index = -1
        for (i in start + 1 until end) {
            val distance = perpendicularDistance(points[i], a, b)
            if (distance > maxDistance) {
                maxDistance = distance
                index = i
            }
        }
        if (maxDistance > epsilon && index > 0) {
            keep[index] = true
            simplifyRange(points, start, index, epsilon, keep)
            simplifyRange(points, index, end, epsilon, keep)
        }
    }

    private fun perpendicularDistance(point: Px, a: Px, b: Px): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq <= 0f) return hypot(point.x - a.x, point.y - a.y)
        val t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSq
        val clamped = t.coerceIn(0f, 1f)
        val projX = a.x + clamped * dx
        val projY = a.y + clamped * dy
        return hypot(point.x - projX, point.y - projY)
    }

    companion object {
        /** 点击判定：按下时长上限。 */
        const val TAP_MAX_MS = 150L

        /**
         * 点击最短按压：系统侧不接受 0 时长，且实测 adb `input tap`（0ms）录进来后
         * 按 30ms 回放，接收侧每一笔都是 30ms。
         */
        const val TAP_MIN_MS = 30L

        /**
         * 滑动最短时长：30ms 的快滑实际只有 27–38ms，按此下限记成 60ms；
         * 本轮实测接收侧 60 笔全部 60ms（见「录制快滑下限」）。
         */
        const val SWIPE_MIN_MS = 60L

        /** 路径简化容差（像素），按设计方案固定 1px。 */
        const val RDP_TOLERANCE_PX = 1f
    }
}

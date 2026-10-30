package com.auto.fairy

import com.auto.fairy.core.compiler.CompiledStroke
import com.auto.fairy.core.model.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势分批语义（时序审查 R11）。
 *
 * 回归点：把「前一笔抬起、后一笔接着按下」的多条 stroke 塞进同一个
 * `GestureDescription` 时，系统把它们当成同一手势的多根手指（pointerId 按下标分配、
 * 共享 downTime），接收方只看到一根手指一直按着。真机实测：40 次相邻点击
 * （间隔 60ms、按住 60ms）只有 1 次 `touchstart` 生效。
 *
 * 规则：只有时间上真正重叠（同一次按压的多指）的笔画才允许同批。
 */
class GestureBatchingTest {

    private fun tapStroke(startMs: Long, durationMs: Long = 60L, fingers: Int = 1) =
        CompiledStroke(
            startMs = startMs,
            durationMs = durationMs,
            paths = List(fingers) { listOf(Point(0.5f, 0.5f)) },
        )

    private fun build(vararg strokes: CompiledStroke, minGapMs: Long = 50L) =
        GestureBatching.build(strokes.toList(), minGapMs)

    @Test
    fun `相邻点击各自独立成批`() {
        // 60ms 长按 + 60ms 间隔 = 首尾相接，曾经被合进同一个手势
        val batches = build(tapStroke(0), tapStroke(60), tapStroke(120))
        assertEquals(3, batches.size)
        assertTrue(batches.all { it.strokes.size == 1 })
        assertEquals(listOf(0L, 60L, 120L), batches.map { it.firstStartMs })
    }

    @Test
    fun `间隔再大也不合批`() {
        val batches = build(tapStroke(0), tapStroke(300), tapStroke(600))
        assertEquals(3, batches.size)
    }

    @Test
    fun `多指同发仍是一次按压`() {
        // 一条多指笔画（3 条 path 共享 startMs）必须留在同一批
        val batches = build(tapStroke(0, fingers = 3))
        assertEquals(1, batches.size)
        assertEquals(3, batches[0].strokes.size)
        // 每条手指都从同一个时刻开始
        assertTrue(batches[0].strokes.all { it.startMs == 0L })
    }

    @Test
    fun `多指步骤与后续单指步骤分开派发`() {
        val batches = build(tapStroke(0, durationMs = 100L, fingers = 2), tapStroke(100))
        assertEquals(2, batches.size)
        assertEquals(2, batches[0].strokes.size)
        assertEquals(1, batches[1].strokes.size)
    }

    @Test
    fun `过近的动作整体后移到最小间隔`() {
        // 按压 20ms、间隔 20ms：把后续动作整体后移到 50ms 间隔，顺序不变
        val batches = build(tapStroke(0, durationMs = 20), tapStroke(20, 20), tapStroke(40, 20))
        assertEquals(3, batches.size)
        assertEquals(listOf(0L, 50L, 100L), batches.map { it.firstStartMs })
    }

    @Test
    fun `后移不得让相邻两笔真的重叠`() {
        // 按压 60ms、间隔 20ms：若只按「起始间隔 50ms」后移，两笔会重叠 40ms，
        // 又被当成同一次多指按压。必须等到上一笔抬起之后才开始。
        val batches = build(tapStroke(0), tapStroke(20), tapStroke(40))
        assertEquals(3, batches.size)
        assertEquals(listOf(0L, 60L, 120L), batches.map { it.firstStartMs })
        // 任何两批之间都不重叠
        val sorted = batches.map { it.firstStartMs }.sorted()
        assertTrue(sorted.zipWithNext().all { (a, b) -> b - a >= 60L })
    }

    @Test
    fun `计划里重叠的两笔会被推成前后接续`() {
        // 长按 200ms 期间又安排了一次 100ms 按压：重排必须把它推到长按抬起之后，
        // 否则两条 stroke 会真的重叠、被当成同一手势的两根手指
        val batches = build(tapStroke(0, durationMs = 200L), tapStroke(10, durationMs = 100L))
        assertEquals(2, batches.size)
        assertEquals(listOf(0L, 200L), batches.map { it.firstStartMs })
        assertEquals(1, batches[0].strokes.size)
        assertEquals(1, batches[1].strokes.size)
    }

    @Test
    fun `单条笔画自成一批`() {
        val batches = build(tapStroke(1_000L))
        assertEquals(1, batches.size)
        assertEquals(1_000L, batches[0].firstStartMs)
    }

    @Test
    fun `按压起点下限取通道下限与上一笔时长的较大者`() {
        // 短按 20ms：下限仍是通道下限 50ms（下面两笔不会挤在一起）
        assertEquals(50L, GestureBatching.startToStartFloorMs(20L, 50L))
        // 60ms 长按：下限是 60ms——否则下一笔会在上一笔还没抬起时按下
        assertEquals(60L, GestureBatching.startToStartFloorMs(60L, 50L))
        // 2s 长按：同一次按压的语义决定下一笔必须等它抬起
        assertEquals(2_000L, GestureBatching.startToStartFloorMs(2_000L, 50L))
    }

    @Test
    fun `计划间隔已经足够时补偿为零`() {
        // 300ms 间隔、60ms 按压：上一笔起点后 300ms 才发下一笔，下限 60ms 早已满足
        val sinceLastStart = 300L
        val floor = GestureBatching.startToStartFloorMs(60L, 50L)
        assertEquals(0L, (floor - sinceLastStart).coerceAtLeast(0L))
        // 首尾相接（起点间隔 60ms = 按压时长）：正好落在下限上，也不该被推后
        assertEquals(0L, (floor - 60L).coerceAtLeast(0L))
        // 起点间隔只剩 20ms：补到下限
        assertEquals(40L, (floor - 20L).coerceAtLeast(0L))
    }

    @Test
    fun `片内后移让真实派发跨度远超计划跨度`() {
        // 20 拍 10ms 长按、pd=0：计划跨度 200ms（一片），后移后末批派发于 950ms，
        // 真实跨度 960ms。暂停/停止的宽限按「最后一笔派发时刻」起算就为这个差值：
        // 按请求时刻起算会在剩余笔画之前收片，而续体已 resume，后续批次被吞掉。
        val strokes = (0 until 20).map { tapStroke(it * 10L, 10L) }
        val batches = build(*strokes.toTypedArray())
        assertEquals(20, batches.size)
        assertEquals(950L, batches.last().firstStartMs)
        assertEquals(960L, batches.last().firstStartMs + 10L)
    }

    @Test
    fun `后移跨度的上界`() {
        // 19 笔零时长（计划首尾相接，被抬到 50ms 网格）+ 末笔 350ms：
        // 末批派发于 950ms、跨到 1300ms，这是宽限基准的上界形状
        // （调度层保证片内笔画不重叠，19 笔同刻那种形状到不了一片）
        val strokes = (0 until 19).map { tapStroke(it.toLong(), 0L) } + tapStroke(19L, 350L)
        val batches = build(*strokes.toTypedArray())
        assertEquals(20, batches.size)
        assertEquals(950L, batches.last().firstStartMs)
        assertTrue(batches.last().firstStartMs + 350L <= 1_350L)
    }
}

package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.CompiledStroke
import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.compiler.Timeline
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {

    private fun tap(durationMs: Long = 0L, x: Float = 0.5f): Step =
        Step.Tap(listOf(Point(x, 0.5f)), durationMs)

    private fun multi(fingers: Int, durationMs: Long = 10L): Step = Step.Multi(
        (0 until fingers).map { i ->
            listOf(Point(0.1f + 0.1f * i, 0.1f), Point(0.1f + 0.1f * i, 0.9f))
        },
        durationMs,
    )

    private fun compile(vararg steps: Step): Timeline = ScriptCompiler.compile(
        Script(id = "s", name = "n", screen = ScreenInfo.DEFAULT, steps = steps.toList(), defaultActionDelayMs = 0L),
    )

    @Test
    fun `循环体只有等待时稳态计划为空片`() {
        val timeline = compile(tap(durationMs = 60L), Step.Loop(0, listOf(Step.Wait(1_000L))))
        // repeatStartIndex == strokes.size 是合法产物：循环体不含笔画，稳态只有时间
        assertEquals(timeline.strokeCount, timeline.repeatStartIndex)
        val schedule = Scheduler(Caps()).schedule(timeline)
        assertTrue(schedule.steady.slices.isEmpty())
        assertEquals(1_000L, schedule.steady.durationMs)
    }

    @Test
    fun `每批不超过 20 条 stroke`() {
        val timeline = compile(Step.Loop(30, listOf(tap())))
        val schedule = Scheduler(Caps()).schedule(timeline)

        assertEquals(2, schedule.first.slices.size)
        assertEquals(20, schedule.first.slices[0].strokeCount)
        assertEquals(10, schedule.first.slices[1].strokeCount)
        assertTrue(schedule.first.slices.all { it.strokeCount <= 20 })
        assertTrue(schedule.first.slices.all { it.strokes.size <= 20 })
    }

    @Test
    fun `每批不超过 60 秒`() {
        val steps = ArrayList<Step>()
        repeat(10) { i ->
            steps.add(tap())
            if (i != 9) steps.add(Step.Wait(7_000L))
        }
        val timeline = compile(*steps.toTypedArray())
        val plan = Scheduler(Caps()).schedule(timeline).first

        assertEquals(10, timeline.strokeCount)
        assertTrue("应从时长处切开", plan.slices.size >= 2)
        for (slice in plan.slices) {
            assertTrue(
                "切片时长 ${slice.durationMs}ms 超过 60000ms",
                slice.durationMs <= 60_000L,
            )
        }
        // 第一批 9 条（第 10 条落在 63s 处），第二批 1 条。
        assertEquals(9, plan.slices[0].strokes.size)
        assertEquals(1, plan.slices[1].strokes.size)
    }

    @Test
    fun `片内 stroke 严格不重叠`() {
        val steps = ArrayList<Step>()
        repeat(30) { i ->
            steps.add(tap(durationMs = 5L))
            if (i != 29) steps.add(Step.Wait(3L))
        }
        val schedule = Scheduler(Caps()).schedule(compile(*steps.toTypedArray()))

        for (slice in schedule.first.slices) {
            for (k in 1 until slice.strokes.size) {
                assertTrue(
                    "slice #${slice.index} 内第 $k 条与上一条重叠",
                    slice.strokes[k].startMs >= slice.strokes[k - 1].endMs,
                )
            }
        }
    }

    @Test
    fun `片计划跨度不含片内后移量`() {
        // 20 拍 10ms 长按、pd=0：计划跨度 200ms 落进一片；后端按起点间隔下限串行派发后
        // 真实跨度 960ms（见 GestureBatchingTest）。这个差值就是 A11yBackend 的暂停/停止
        // 宽限必须按「最后一笔派发时刻」起算的原因。
        val timeline = compile(Step.Loop(20, listOf(tap(durationMs = 10L))))
        val plan = Scheduler(Caps(maxSliceMs = 400L)).schedule(timeline).first

        assertEquals(1, plan.slices.size)
        assertEquals(20, plan.slices[0].strokeCount)
        assertEquals(200L, plan.slices[0].durationMs)
    }

    @Test
    fun `重叠时间轴在调度入口被拒绝`() {
        val p = listOf(listOf(Point(0.5f, 0.5f)))
        val overlapping = Timeline(
            "t",
            listOf(CompiledStroke(0, 500, p), CompiledStroke(300, 500, p)),
            800L,
            -1,
            0L,
        )
        assertThrows(IllegalStateException::class.java) {
            Scheduler(Caps()).schedule(overlapping)
        }
    }

    @Test
    fun `多指按手指数计入批次预算`() {
        val steps = ArrayList<Step>()
        repeat(7) { steps.add(multi(fingers = 3)) }
        val schedule = Scheduler(Caps()).schedule(compile(*steps.toTypedArray()))

        assertEquals(2, schedule.first.slices.size)
        assertEquals(18, schedule.first.slices[0].strokeCount) // 6 个三指手势
        assertEquals(3, schedule.first.slices[1].strokeCount)
        assertTrue(schedule.first.slices.all { it.strokeCount <= 20 })
    }

    @Test
    fun `单条笔画超过批次上限直接报错`() {
        val timeline = compile(tap(durationMs = 60_001L))
        assertThrows(IllegalArgumentException::class.java) {
            Scheduler(Caps()).schedule(timeline)
        }
    }

    @Test
    fun `笔画时长恰好等于批次上限仍合法`() {
        // 上限是「不超过」：60000ms 合法、60001ms 拒绝（见上一条），边界不能靠下一条碰巧覆盖
        val exact = Scheduler(Caps()).schedule(
            compile(Step.Tap(listOf(Point(0.5f, 0.5f)), 60_000L, preDelayMs = 60_000L)),
        )
        assertEquals(1, exact.first.slices.size)
        assertEquals(60_000L, exact.first.slices[0].durationMs)
        // 动作延迟只是时间轴上的空白段，不占批次时长：60s 延迟 + 60s 笔画仍是一批
        assertEquals(60_000L, exact.first.slices[0].startMs)
        assertEquals(1, exact.first.slices[0].strokes.size)
    }

    @Test
    fun `动作延迟占位但不会撑爆批次时长`() {
        // 每个点击前等 5s：若把延迟算进批次跨度而不切分，一批会远超 60s 上限
        val steps = (0 until 20).map {
            Step.Tap(listOf(Point(0.5f, 0.5f)), 100L, preDelayMs = 5_000L)
        }
        val plan = Scheduler(Caps()).schedule(compile(*steps.toTypedArray())).first

        assertTrue("应按时长切分", plan.slices.size >= 2)
        assertTrue(
            "切片时长 ${plan.slices.map { it.durationMs }} 越界",
            plan.slices.all { it.durationMs <= 60_000L },
        )
        // 片首时刻必须落在首条笔画（含延迟）的位置，不能在延迟段里提前动手
        assertEquals(5_000L, plan.slices[0].startMs)
        assertEquals(0L, plan.slices[0].strokes[0].startMs)
    }

    @Test
    fun `无限循环生成首轮与稳态两套计划`() {
        val timeline = compile(
            tap(durationMs = 100L),
            Step.Loop(0, listOf(tap(durationMs = 50L), Step.Wait(50L))),
        )
        val schedule = Scheduler(Caps(maxStrokesPerBatch = 1)).schedule(timeline)

        assertEquals(2, schedule.first.slices.size)
        assertEquals(1, schedule.steady.slices.size)
        assertEquals(100L, schedule.steady.durationMs)
        assertEquals(0L, schedule.steady.slices[0].startMs)
        // 稳态计划以 repeatStart 为 0 点，片内时间参数必须重新归零。
        assertEquals(0L, schedule.steady.slices[0].strokes[0].startMs)
    }
}

package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.compiler.Timeline
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切片跨度协商（[Caps.maxSliceMs]）：片长决定暂停/停止的生效延迟，是回放响应性的来源。
 */
class SliceSpanTest {

    private fun compile(vararg steps: Step): Timeline = ScriptCompiler.compile(
        Script(id = "s", name = "n", screen = ScreenInfo.DEFAULT, steps = steps.toList(), defaultActionDelayMs = 0L),
    )

    private fun tap(durationMs: Long = 60L, x: Float = 0.5f): Step =
        Step.Tap(listOf(Point(x, 0.5f)), durationMs)

    @Test
    fun `默认不限跨度时保持原有切片`() {
        val steps = ArrayList<Step>()
        repeat(5) { i ->
            steps.add(tap())
            if (i != 4) steps.add(Step.Wait(2_000L))
        }
        val plan = Scheduler(Caps()).schedule(compile(*steps.toTypedArray())).first

        assertEquals("maxSliceMs=0 不该改变切片行为", 1, plan.slices.size)
        assertTrue(plan.slices[0].durationMs > 2_000L)
    }

    @Test
    fun `maxSliceMs 把时间轴切成小片`() {
        val steps = ArrayList<Step>()
        repeat(5) { i ->
            steps.add(tap())
            if (i != 4) steps.add(Step.Wait(2_000L))
        }
        val plan = Scheduler(Caps(maxSliceMs = 400L)).schedule(compile(*steps.toTypedArray())).first

        assertEquals(5, plan.slices.size)
        for (slice in plan.slices) {
            assertTrue(
                "切片 ${slice.index} 跨度 ${slice.durationMs}ms 超过 400ms",
                slice.durationMs <= 400L,
            )
        }
        // 每个切片都要保住自己的绝对起点，切片只能变细，不能挪动时间轴
        assertEquals(0L, plan.slices[0].startMs)
        assertEquals(2_060L, plan.slices[1].startMs)
    }

    @Test
    fun `maxSliceMs 不切断单条长笔画`() {
        val swipe = Step.Swipe(
            listOf(Point(0.5f, 0.8f), Point(0.5f, 0.2f)),
            durationMs = 1_000L,
        )
        val plan = Scheduler(Caps(maxSliceMs = 400L)).schedule(compile(swipe)).first

        assertEquals(1, plan.slices.size)
        assertEquals(1_000L, plan.slices[0].durationMs)
    }

    @Test
    fun `片间只保留脚本自身的空档`() {
        // 片间不加间隔：调度层只管切片，按压起点下限由后端统一兜住（片内由
        // GestureBatching、片间由 dispatchBatch 的起点下限）。
        // 时间轴自带 100ms 空档（点击 60 + 等待 100）：两片之间的差值必须**恰好**
        // 等于脚本自己的空档；一旦调度层又塞进片间间隔，这里立刻变红。
        val steps = ArrayList<Step>()
        repeat(45) { i ->
            steps.add(tap())
            if (i != 44) steps.add(Step.Wait(100L))
        }
        val plan = Scheduler(Caps(maxSliceMs = 400L)).schedule(compile(*steps.toTypedArray())).first

        assertTrue("应切出多片", plan.slices.size > 1)
        for (k in 1 until plan.slices.size) {
            assertEquals(
                "第 $k 片与上一片之间被塞进了额外间隔",
                100L,
                plan.slices[k].startMs - plan.slices[k - 1].endMs,
            )
        }
        assertTrue("计划时长必须覆盖最后一片", plan.durationMs >= plan.slices.last().endMs)
    }

    @Test
    fun `maxSliceMs 与手指数上限同时生效`() {
        val steps = ArrayList<Step>()
        repeat(30) { steps.add(tap()) }
        val plan = Scheduler(Caps(maxSliceMs = 400L, maxStrokesPerBatch = 4))
            .schedule(compile(*steps.toTypedArray()))
            .first

        assertEquals(8, plan.slices.size)
        for (slice in plan.slices) {
            assertTrue(slice.strokeCount <= 4)
            assertTrue(slice.strokes.size <= 4)
        }
    }
}

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
 * 边界场景：超长脚本、深嵌套循环、双无限循环、极大/极小参数。
 *
 * 这些形态不是「正常脚本」，但都是用户手编/导入能造出来的：一旦在此处失效，
 * 表现是丢步、串步或计划随轮次增长，用户看不出原因。这里只测不被其它单测覆盖的
 * 那部分——展开顺序、计划规模、稳态计划是否随轮次变化。
 */
class BoundaryScenarioTest {

    private fun compile(steps: List<Step>, loopTimes: Int = 0): Timeline =
        ScriptCompiler.compile(
            Script(
                id = "s_b",
                name = "b",
                screen = ScreenInfo.DEFAULT,
                steps = steps,
                loopTimes = loopTimes,
                defaultActionDelayMs = 0L,
            ),
        )

    private fun tap(durationMs: Long, preDelayMs: Long = 0L, x: Float = 0.5f): Step =
        Step.Tap(listOf(Point(x, 0.5f)), durationMs, preDelayMs = preDelayMs)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1L)
        }
        return condition()
    }

    /** 240 步（>200）单轮播放：一条不少、顺序不变、时间轴步距恒定。 */
    @Test
    fun `超长脚本单轮按序播放不漏步`() {
        val count = 240
        val steps = (0 until count).map { i -> tap(durationMs = 1L, preDelayMs = 1L, x = (i + 1) / 241f) }
        val timeline = compile(steps, loopTimes = 1)
        assertEquals(count, timeline.strokeCount)
        assertEquals(count * 2L, timeline.durationMs)

        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue(engine.start(timeline, loopTimes = 1))
        assertTrue("240 步单轮应在数秒内跑完", engine.awaitIdle(20_000L))

        val records = backend.strokeRecords()
        assertEquals(count, records.size)
        // 步序：每笔相对首笔的计划偏移与展开顺序一致，没有任何一笔记到别的步骤上
        // （strokeStartMs 是片内相对值，240 步会被切成多片，因此按 expectedNs 折算）
        val base = records.first().expectedNs
        assertEquals(
            (0 until count).map { it * 2L },
            records.map { (it.expectedNs - base) / 1_000_000L },
        )
        assertEquals((0 until count).toList(), records.map { it.ordinal })
        assertTrue("全部落在同一轮", records.all { it.cycle == 0L })
        // 计划时刻严格等距；实际执行时刻必须单调
        assertTrue(
            "实际执行时刻必须单调递增",
            records.map { it.actualNs }.zipWithNext().all { (a, b) -> b > a },
        )
    }

    /** 三层嵌套（2×3×2）：完全展开成 12 步，步距恒定。 */
    @Test
    fun `三层嵌套循环完整展开并按序播放`() {
        val innermost = Step.Loop(2, listOf(tap(durationMs = 1L, preDelayMs = 1L)))
        val middle = Step.Loop(3, listOf(innermost))
        val outer = Step.Loop(2, listOf(middle))
        val timeline = compile(listOf(outer), loopTimes = 1)

        assertEquals(12, timeline.strokeCount)
        // 每步 = 动作延迟 1ms + 动作 1ms，首步落在 1ms
        assertEquals((0 until 12).map { it * 2L + 1 }, timeline.strokes.map { it.startMs })
        assertEquals(24L, timeline.durationMs)

        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue(engine.start(timeline, loopTimes = 1))
        assertTrue(engine.awaitIdle(10_000L))
        val records = backend.strokeRecords()
        assertEquals(12, records.size)
        // 记录里的 startMs 是**片内相对值**（首条恒为 0）
        assertEquals((0 until 12).map { it * 2L }, records.map { it.strokeStartMs })
    }

    /**
     * 双无限（脚本内 `loop(0)` + 脚本级 lp=0）：跑多轮后稳态计划不增长。
     *
     * 编译期只展开循环体一次，运行期靠稳态计划反复平移基准；若某处按轮次
     * 重新编译/累积，这里会看到 plan 规模或每轮笔画数变化。
     */
    @Test
    fun `双无限循环连跑多轮时稳态计划不增长`() {
        val timeline = compile(
            listOf(Step.Loop(0, listOf(tap(durationMs = 2L, preDelayMs = 2L)))),
            loopTimes = 0,
        )
        val schedule = Scheduler(Caps()).schedule(timeline)
        assertEquals(1, schedule.first.slices.size)
        assertEquals(1, schedule.steady.slices.size)
        assertEquals(4L, schedule.steady.durationMs)

        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue(engine.start(timeline, loopTimes = 0))
        assertTrue(waitUntil(10_000L) { engine.cursor().cycle >= 12L })
        assertTrue(engine.stop())
        assertTrue(engine.awaitIdle(5_000L))

        val records = backend.strokeRecords()
        // 每个周期恰好一笔；笔画数与轮数一一对应，没有额外重复或累积
        val cycles = records.map { it.cycle }.distinct()
        assertEquals(records.size, cycles.size)
        assertTrue("跑了 ${records.size} 轮", records.size >= 12)
        assertEquals((0 until records.size).map { it.toLong() }, records.map { it.cycle })
        // 稳态周期恒定：相邻两轮起点间隔都等于一个周期
        val gaps = records.map { it.expectedNs }.zipWithNext { a, b -> (b - a) / 1_000_000L }
        assertTrue("轮间隔应恒为 4ms：$gaps", gaps.all { it == 4L })
    }

    /** 动作延迟取上限 60s、动作时长取最小 1ms：一整片、不拆、不截断。 */
    @Test
    fun `极大动作延迟与 1ms 动作时长仍可调度`() {
        val timeline = compile(listOf(tap(durationMs = 1L, preDelayMs = 60_000L)), loopTimes = 1)
        assertEquals(60_001L, timeline.durationMs)
        val plan = Scheduler(Caps(maxSliceMs = 400L)).schedule(timeline).first
        // 单条笔画不能被切片切开（否则手势语义被改变），只能独占一片
        assertEquals(1, plan.slices.size)
        assertEquals(60_000L, plan.slices[0].startMs)
        assertEquals(60_001L, plan.slices[0].endMs)
        assertEquals(1L, plan.slices[0].strokes[0].durationMs)
    }

    /** 单步循环取解析上限 rp=9999：展开成 9999 条笔画，切片不超每批 20 指。 */
    @Test
    fun `单步重复上限展开为 9999 条笔画`() {
        val timeline = compile(
            listOf(
                Step.Tap(
                    points = listOf(Point(0.5f, 0.5f)),
                    durationMs = 2L,
                    preDelayMs = 2L,
                    repeat = 9_999,
                ),
            ),
            loopTimes = 1,
        )

        assertEquals(9_999, timeline.strokeCount)
        assertEquals(9_999 * 4L, timeline.durationMs)
        val plan = Scheduler(Caps()).schedule(timeline).first
        assertTrue("每批不得超过 20 指", plan.slices.all { it.strokeCount <= 20 })
        assertEquals(9_999, plan.slices.sumOf { it.strokes.size })
    }

    /** 手编脚本把 rp 写成天文数字：解析期夹到上限，而不是把内存打爆。 */
    @Test
    fun `超范围单步重复次数在解析期夹到上限`() {
        val json = """
            {"v":1,"id":"s_big","name":"big","lp":1,"ad":0,
             "screen":{"w":1080,"h":2400,"dpi":420,"rot":0},
             "steps":[{"t":"tap","d":1,"pd":0,"rp":100000,"p":[[0.5,0.5]]}]}
        """.trimIndent()
        val step = Script.fromJson(json).steps.single() as Step.Tap
        assertEquals(9_999, step.repeat)
    }

    /** 展开后越过 stroke 上限的脚本在编译期被拒：手编「9999 次 × 21 轮」不该把内存打爆。 */
    @Test
    fun `展开超过 stroke 上限的脚本编译期被拒`() {
        // 9,999 × 21 = 209,979 > ScriptCompiler.MAX_COMPILED_STROKES
        val repeated = Step.Tap(listOf(Point(0.5f, 0.5f)), 1L, preDelayMs = 0L, repeat = 9_999)
        val script = Script(
            id = "s_huge",
            name = "huge",
            screen = ScreenInfo.DEFAULT,
            steps = listOf(Step.Loop(times = 21, body = listOf(repeated))),
            loopTimes = 1,
            defaultActionDelayMs = 0L,
        )
        val error = runCatching { ScriptCompiler.compile(script) }.exceptionOrNull()
        assertTrue("应抛出超上限错误，实际：$error", error is IllegalArgumentException)
    }
}

package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.compiler.Timeline
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户报的「乱循环 / 乱执行」里，之前没有单测覆盖的几条控制流：
 *
 * 1. 外层次数（loopTimes）叠加内层 Loop(n) 的迭代周期；
 * 2. 多指步骤的动作延迟是「每轮一次」，不是「每根手指一次」；
 * 3. 用户主动停止不是错误（后端断开/回调超时才是）。
 */
class LoopAndControlTest {

    private fun compile(steps: List<Step>): Timeline = ScriptCompiler.compile(
        Script(
            id = "s",
            name = "n",
            screen = ScreenInfo.DEFAULT,
            steps = steps,
            defaultActionDelayMs = 0L,
        ),
    )

    private fun tap(ms: Long, pd: Long = 0L, x: Float = 0.5f): Step =
        Step.Tap(listOf(Point(x, 0.5f)), ms, preDelayMs = pd)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1L)
        }
        return condition()
    }

    @Test
    fun `外层次数与内层循环叠加后每轮周期一致`() {
        // 一轮 = tap(pd=100,d=50) + loop(3){ tap(pd=50,d=50) }
        //     = 100+50 + 3*(50+50) = 450ms
        val timeline = compile(
            listOf(
                tap(50L, pd = 100L),
                Step.Loop(3, listOf(tap(50L, pd = 50L))),
            ),
        )
        assertEquals(450L, timeline.durationMs)
        assertEquals(4, timeline.strokeCount)

        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue(engine.start(timeline, loopTimes = 3))
        assertTrue(engine.awaitIdle(10_000L))

        val records = backend.strokeRecords()
        assertEquals(12, records.size)
        val perCycle = records.groupBy { it.cycle }
        assertEquals(setOf(0L, 1L, 2L), perCycle.keys)
        // 每轮的 4 笔相对该轮起点的偏移固定：0 / 100 / 200 / 300
        for ((_, list) in perCycle) {
            val base = list.first().expectedNs
            val offsets = list.map { (it.expectedNs - base) / 1_000_000L }
            assertEquals(listOf(0L, 100L, 200L, 300L), offsets)
        }
        // 轮起点间隔等于叠加后的周期 450ms，不逐轮漂移
        val cycleStarts = perCycle.toSortedMap().values.map { it.first().expectedNs }
        assertEquals(listOf(450L, 450L), cycleStarts.zipWithNext { a, b -> (b - a) / 1_000_000L })
    }

    @Test
    fun `多指的动作延迟每轮只等一次`() {
        val fingers = (0 until 3).map { i ->
            listOf(Point(0.2f + 0.2f * i, 0.3f), Point(0.2f + 0.2f * i, 0.7f))
        }
        val timeline = compile(
            listOf(Step.Multi(fingers, durationMs = 100L, preDelayMs = 300L, repeat = 2)),
        )
        // 每轮 300 + 100；两轮共 800ms，每轮的三根手指共享同一个起点
        assertEquals(800L, timeline.durationMs)
        // 一条多指笔画在时间轴上只占一个位置（3 条 path 同发），两轮共 2 条
        assertEquals(2, timeline.strokeCount)
        assertEquals(
            listOf(300L, 700L),
            timeline.strokes.map { it.startMs },
        )
        assertTrue(timeline.strokes.all { it.paths.size == 3 })

        // 多指不能被切片拆开，也不能把「每根手指」当成一次延迟
        val plan = Scheduler(Caps(maxSliceMs = 400L)).schedule(timeline).first
        assertEquals(2, plan.slices.size)
        assertEquals(1, plan.slices[0].strokes.size)
        assertEquals(3, plan.slices[0].strokeCount)
        assertEquals(0L, plan.slices[0].strokes[0].startMs)

        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue(engine.start(timeline))
        assertTrue(engine.awaitIdle(10_000L))
        // FakeBackend 按 CompiledStroke 记录（一条多指笔画算一次注入），两轮共两条
        val records = backend.strokeRecords()
        assertEquals(2, records.size)
        // 计划间隔 = 700-300 = 400ms；实际派发允许首笔冷启动的少量抖动
        val planGapMs = (records[1].expectedNs - records[0].expectedNs) / 1_000_000L
        assertEquals(400L, planGapMs)
        val actualGapMs = (records[1].actualNs - records[0].actualNs) / 1_000_000L
        assertTrue("两轮实际间隔 ${actualGapMs}ms", actualGapMs in 385L..415L)
        // 每一条都是三指同发（paths 就是真正交给系统的手指数）
        assertTrue(plan.slices.all { slice -> slice.strokes.all { it.paths.size == 3 } })
    }

    @Test
    fun `外层次数收口内层无限循环`() {
        // NEST 形态：外层 tap 播一次，随后进入内层无限循环。
        // 编译只展开一次循环体，稳态周期 = 循环体一次迭代。
        val timeline = compile(
            listOf(
                tap(50L, pd = 100L, x = 0.2f),
                Step.Loop(0, listOf(tap(50L, pd = 50L, x = 0.8f))),
            ),
        )
        // 前缀 150ms + 稳态 100ms；稳态起点是内层循环体首笔
        assertEquals(250L, timeline.durationMs)
        assertEquals(100L, timeline.repeatPeriodMs)
        assertEquals(150L, timeline.repeatStartMs)
        assertTrue(timeline.isRepeating)

        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        // 引擎 loopTimes=3：跑满 3 次稳态迭代必须退出，不能永远转
        assertTrue(engine.start(timeline, loopTimes = 3))
        assertTrue(engine.awaitIdle(10_000L))

        val records = backend.strokeRecords()
        // 第 1 笔前缀 + 3 次稳态 = 4 笔
        assertEquals(4, records.size)
        val perCycle = records.groupBy { it.cycle }
        assertEquals(setOf(0L, 1L, 2L), perCycle.keys)
        // 第 0 轮含前缀（150ms），所以它的稳态笔落在 200ms；此后每轮一个
        // 稳态周期（100ms），轮起点间隔不漂移。
        val steadyStarts = perCycle.toSortedMap().values.map { it.first().expectedNs }
        val base = steadyStarts.first()
        // 第 0 轮稳态笔落在 200ms（前缀 150ms + 稳态内 pd 50ms）。
        // 第 0→1 轮间隔 200ms：那一跳含整个前缀；此后每轮一个稳态周期 100ms。
        assertEquals(
            listOf(200L, 400L, 500L),
            steadyStarts.map { (it - base) / 1_000_000L + 200L },
        )
        assertEquals(
            listOf(200L, 100L),
            steadyStarts.zipWithNext { a, b -> (b - a) / 1_000_000L },
        )
        assertFalse(engine.isRunning)
    }

    @Test
    fun `用户停止不记录错误`() {
        val engine = PlaybackEngine(FakeBackend())
        var exits = 0
        engine.onExited = { exits++ }
        val timeline = compile(listOf(tap(20L), Step.Wait(20L)))

        assertTrue(engine.start(timeline, loopTimes = 0))
        assertTrue(waitUntil(3_000L) { engine.cursor().cycle >= 1L })
        assertTrue(engine.stop())
        assertTrue(engine.awaitIdle(5_000L))

        // 停止走的是中断路径，不能被当成「手势回调超时」之类的失败上报
        assertNull("停止不应留下错误", engine.lastError())
        assertEquals(1, exits)
    }

    @Test
    fun `注入失败会留下错误供界面上报`() {
        val backend = FakeBackend().apply { failOnSlice = 0 }
        val engine = PlaybackEngine(backend)
        val reported = ArrayList<Throwable?>()
        engine.onExited = { reported.add(engine.lastError()) }

        assertTrue(engine.start(compile(listOf(tap(20L)))))
        assertTrue(engine.awaitIdle(5_000L))

        assertEquals(1, reported.size)
        val error = reported.single()
        assertNotNull("失败必须留下原因", error)
        assertTrue(error is IllegalStateException)
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)
    }

    @Test
    fun `含无限循环的脚本按脚本级次数收口`() {
        // 脚本自带 loop(0)：此前 loopTimes 被无条件忽略，界面写「循环 3 次」却永远不停
        // （真机实测 47 秒后仍在点）。现在按稳态周期计数，累计 3 轮即停。
        val timeline = compile(listOf(Step.Loop(0, listOf(tap(20L), Step.Wait(80L)))))
        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue(engine.start(timeline, loopTimes = 3))
        assertTrue("3 轮后必须自己停", engine.awaitIdle(10_000L))

        val records = backend.strokeRecords()
        assertEquals("每轮一条笔画，共 3 轮", 3, records.size)
        assertEquals(listOf(0L, 1L, 2L), records.map { it.cycle })
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)
    }

    @Test
    fun `含无限循环且次数为 0 时继续跑`() {
        // 0 = 无限（默认值）：行为不变，仍然一直跑到用户停止
        val timeline = compile(listOf(Step.Loop(0, listOf(tap(20L), Step.Wait(80L)))))
        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue(engine.start(timeline, loopTimes = 0))
        assertTrue(waitUntil(5_000L) { backend.strokeRecords().size >= 5 })
        assertTrue(engine.isRunning)
        assertTrue(engine.stop())
        assertTrue(engine.awaitIdle(5_000L))
    }

    @Test
    fun `无限循环里点停止后不再产生笔画`() {
        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        val timeline = compile(listOf(Step.Loop(0, listOf(tap(20L), Step.Wait(80L)))))
        assertTrue(engine.start(timeline, loopTimes = 0))
        assertTrue(waitUntil(5_000L) { backend.strokeRecords().size >= 3 })

        assertTrue(engine.stop())
        assertTrue(engine.awaitIdle(5_000L))
        val countAtStop = backend.strokeRecords().size
        Thread.sleep(200L)
        // 停止是同步收口的：等待期内不得再有新笔画落地
        assertEquals(countAtStop, backend.strokeRecords().size)
    }
}

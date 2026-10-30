package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackEngine.cursor] 与真实轮次的吻合度：界面「第 N 巡 / 已完成 N 次」整个来自它。
 *
 * 旧实现在**派发**时写巡数：每巡以长等待开头时，新一巡已经开始、首笔还没派发，
 * 读数却停在上一巡（真机 2s 前摇；本测试的 100ms 前摇就够复现，旧实现实测
 * 43% 的采样点与时间轴不一致、跑完还少 1 巡）。现在巡数在迭代边界推进。
 */
class CursorProgressTest {

    private fun compile(steps: List<Step>): com.auto.fairy.core.compiler.Timeline = ScriptCompiler.compile(
        Script(id = "s", name = "n", screen = ScreenInfo.DEFAULT, steps = steps, defaultActionDelayMs = 0L),
    )

    private fun tap(ms: Long) = Step.Tap(listOf(Point(0.5f, 0.5f)), ms)

    /** 每 1ms 采一次，与「按时间轴已走完的巡数」逐点比对；边界 ±10ms 内不判（读数与时钟不同刻）。 */
    @Test
    fun `游标与真实轮次逐点一致`() {
        val periodMs = 220L
        val rounds = 8
        // 每巡 100ms 前摇 + 三笔动作 + 20ms 尾巴
        val steps = listOf(
            Step.Wait(100L), tap(20L), Step.Wait(20L), tap(20L), Step.Wait(20L), tap(20L), Step.Wait(20L),
        )
        val timeline = compile(steps)
        assertEquals(periodMs, timeline.durationMs)
        val perCycle = timeline.strokeCount

        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        val samples = ArrayList<LongArray>()
        val sampler = Thread {
            while (engine.isRunning) {
                val t1 = System.nanoTime()
                val cursor = engine.cursor().cycle
                val dispatched = backend.strokeRecords().size.toLong()
                val t2 = System.nanoTime()
                // 采样本身跨越太久就丢掉：游标属于哪一刻说不清
                if (t2 - t1 < 2_000_000L) {
                    synchronized(samples) { samples.add(longArrayOf(t1, cursor, dispatched)) }
                }
                Thread.sleep(1L)
            }
        }
        assertTrue(engine.start(timeline, loopTimes = rounds))
        sampler.start()
        assertTrue("回放超时", engine.awaitIdle(20_000L))
        sampler.join(2_000L)

        val anchor = backend.strokeRecords().first().expectedNs - 100L * 1_000_000L
        val periodNs = periodMs * 1_000_000L
        val margin = 10_000_000L
        var checked = 0
        var mismatch = 0
        synchronized(samples) {
            for (s in samples) {
                val delta = s[0] - anchor
                if (delta < 0 || delta % periodNs < margin) continue
                checked++
                val expected = delta / periodNs
                if (s[1] != expected && mismatch++ < 3) {
                    println("游标 ${s[1]} 与第 ${expected + 1} 巡不一致（t=${delta / 1_000_000}ms）")
                }
                // 游标不能声称某巡已完成，而该巡的笔画还没派发出去
                assertTrue("游标跑在派发前面：cursor=${s[1]} dispatched=${s[2]}", s[1] * perCycle <= s[2])
            }
        }
        assertTrue("采样太少（$checked），测不出问题", checked > 300)
        assertEquals("游标与时间轴不一致的采样点", 0, mismatch)
        assertEquals("跑完必须等于总巡数", rounds.toLong(), engine.cursor().cycle)
    }

    /** 脚本自带 loop(0)（无限）时，脚本级次数仍然生效，跑完游标 = 次数。 */
    @Test
    fun `无限循环按脚本级次数收口且游标等于次数`() {
        val timeline = compile(listOf(Step.Loop(0, listOf(tap(20L), Step.Wait(80L)))))
        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue(engine.start(timeline, loopTimes = 3))
        assertTrue(engine.awaitIdle(10_000L))
        assertEquals(listOf(0L, 1L, 2L), backend.strokeRecords().map { it.cycle })
        assertEquals(3L, engine.cursor().cycle)
    }
}

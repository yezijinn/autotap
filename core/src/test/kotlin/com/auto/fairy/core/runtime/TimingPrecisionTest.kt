package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * M1 验收硬指标：笔画实际执行时刻 vs 计划时刻
 * p95 < 3ms、max < 10ms（FakeBackend 端到端记录，含引擎唤醒延迟）。
 */
class TimingPrecisionTest {

    @Test
    fun `笔画执行时刻 p95 小于 3ms 且 max 小于 10ms`() {
        // 预热：让 JIT 编译回放热循环，避免把编译停顿计入验收样本。
        play(count = 150, gapMs = 4L)

        val records = play(count = 500, gapMs = 4L)
        assertEquals(500, records.size)

        val deviations = records.map { it.deviationNs }
        val p50 = percentile(deviations, 0.50)
        val p95 = percentile(deviations, 0.95)
        val p99 = percentile(deviations, 0.99)
        val max = deviations.maxOrNull() ?: 0L
        val min = deviations.minOrNull() ?: 0L
        val early = deviations.count { it < 0L }

        println(
            "时序偏差: n=${deviations.size} min=${ms(min)} p50=${ms(p50)} p95=${ms(p95)} " +
                "p99=${ms(p99)} max=${ms(max)} 提前执行数=$early spinPad=${DeadlineTimer.spinPadNs / 1_000_000}ms",
        )

        assertTrue("p95=${ms(p95)}ms 超过 3ms", p95 < 3_000_000L)
        assertTrue("max=${ms(max)}ms 超过 10ms", max < 10_000_000L)
        // 自旋兜底不允许系统性提前执行（提前说明计划时刻算错）。
        assertTrue("存在明显提前执行: min=${ms(min)}ms", min > -2_000_000L)
    }

    private fun play(count: Int, gapMs: Long): List<FakeBackend.StrokeRecord> {
        val steps = ArrayList<Step>(count * 2)
        repeat(count) { i ->
            steps.add(Step.Tap(listOf(Point(0.5f, 0.5f)), 1L))
            if (i != count - 1) steps.add(Step.Wait(gapMs - 1L))
        }
        val timeline = ScriptCompiler.compile(
            Script(id = "s_timing", name = "timing", screen = ScreenInfo.DEFAULT, steps = steps, defaultActionDelayMs = 0L),
        )
        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        assertTrue("启动失败", engine.start(timeline, loopTimes = 1))
        assertTrue("回放超时", engine.awaitIdle(60_000L))
        return backend.strokeRecords()
    }

    private fun percentile(values: List<Long>, q: Double): Long {
        val sorted = values.sorted()
        val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun ms(ns: Long): Double = ns / 1_000_000.0
}

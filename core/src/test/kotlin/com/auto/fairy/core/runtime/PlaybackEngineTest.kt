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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackEngineTest {

    private fun compile(steps: List<Step>): Timeline = ScriptCompiler.compile(
        Script(id = "s", name = "n", screen = ScreenInfo.DEFAULT, steps = steps, defaultActionDelayMs = 0L),
    )

    private fun taps(count: Int, gapMs: Long = 50L, tapMs: Long = 5L): List<Step> {
        val steps = ArrayList<Step>(count * 2)
        repeat(count) { i ->
            steps.add(Step.Tap(listOf(Point(0.5f, 0.5f)), tapMs))
            if (i != count - 1) steps.add(Step.Wait(gapMs - tapMs))
        }
        return steps
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1L)
        }
        return condition()
    }

    @Test
    fun `并发 Start 只有第一次成功`() {
        val engine = PlaybackEngine(FakeBackend())
        val timeline = compile(listOf(Step.Wait(400L)))
        val gate = CountDownLatch(1)
        val results = BooleanArray(4)
        val threads = (0 until 4).map { i ->
            Thread {
                gate.await()
                results[i] = engine.start(timeline)
            }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join(5_000L) }

        assertEquals(1, results.count { it })
        assertTrue(engine.isRunning)
        assertTrue(engine.runToken >= 1L)

        engine.stop()
        assertTrue(engine.awaitIdle(5_000L))
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)
    }

    @Test
    fun `运行中再次 Start 被拒绝`() {
        val engine = PlaybackEngine(FakeBackend())
        val timeline = compile(listOf(Step.Wait(500L)))

        assertTrue(engine.start(timeline))
        assertFalse(engine.start(timeline))
        assertTrue(engine.stop())
        assertTrue(engine.awaitIdle(5_000L))
    }

    @Test
    fun `暂停续播平移时间基准且无漂移`() {
        val backend = FakeBackend(Caps(maxStrokesPerBatch = 4))
        val engine = PlaybackEngine(backend)
        val timeline = compile(taps(count = 40, gapMs = 50L, tapMs = 5L))

        assertTrue(engine.start(timeline))
        assertTrue("等待进入稳定回放", waitUntil(3_000L) { backend.strokeRecords().size >= 6 })

        assertTrue(engine.pause())
        assertTrue(
            "等待暂停在切片边界生效",
            waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.PAUSED },
        )
        val pausedAckNs = System.nanoTime()
        Thread.sleep(200L)
        val resumeCallNs = System.nanoTime()
        assertTrue(engine.resume())
        assertTrue(engine.awaitIdle(10_000L))

        val records = backend.strokeRecords()
        assertEquals(40, records.size)
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)

        val firstAfter = records.indexOfFirst { it.actualNs >= resumeCallNs }
        assertTrue("恢复后应有剩余笔画", firstAfter in 1 until records.size)

        // 暂停期间不得有任何笔画被执行。
        val lastBefore = records[firstAfter - 1]
        assertTrue(
            "暂停窗口内仍有笔画执行",
            records.none { it.actualNs in (lastBefore.actualNs + 1) until pausedAckNs },
        )

        // 暂停时长必须完整插入时间轴（时间基准平移）。暂停从切片边界生效，
        // 这里以引擎确认 PAUSED 之后再计时，避免把「等待切片结束」算进暂停。
        val gapNs = records[firstAfter].actualNs - lastBefore.actualNs
        assertTrue("暂停未插入时间轴: gap=${gapNs / 1_000_000.0}ms", gapNs >= 180_000_000L)
        assertTrue("暂停插入过多: gap=${gapNs / 1_000_000.0}ms", gapNs <= 500_000_000L)

        // 恢复后相对时间轴不得漂移：相邻笔画间隔应回到 50ms。
        val after = records.subList(firstAfter, records.size)
        val gaps = after.zipWithNext { a, b -> b.actualNs - a.actualNs }
        val maxGapError = gaps.maxOfOrNull { kotlin.math.abs(it - 50_000_000L) } ?: 0L
        assertTrue(
            "恢复后间隔漂移 ${maxGapError / 1_000_000.0}ms",
            maxGapError < 3_000_000L,
        )

        // 恢复后的计划偏差（含引擎唤醒）同样应在精度预算内。
        val deviations = after.map { kotlin.math.abs(it.deviationNs) }.sorted()
        val p95 = deviations[(deviations.size * 95 / 100).coerceAtMost(deviations.size - 1)]
        assertTrue("恢复后 p95 偏差 ${p95 / 1_000_000.0}ms", p95 < 3_000_000L)
    }

    @Test
    fun `1000 次循环全跑完不丢步骤`() {
        val body = listOf(
            Step.Tap(listOf(Point(0.5f, 0.5f)), 0L),
            Step.Tap(listOf(Point(0.5f, 0.5f)), 0L),
            Step.Wait(0L),
        )
        val timeline = compile(listOf(Step.Loop(1_000, body)))
        assertEquals(2_000, timeline.strokeCount)

        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)

        assertTrue(engine.start(timeline, loopTimes = 1))
        assertTrue(engine.awaitIdle(20_000L))

        val records = backend.strokeRecords()
        assertEquals(2_000, records.size)
        assertEquals((0 until 2_000).toList(), records.map { it.ordinal })
        assertTrue(records.all { it.cycle == 0L })
        // 全部在计划时刻之后执行（不允许整段提前）。
        assertTrue(records.all { it.actualNs >= it.expectedNs })
    }

    @Test
    fun `无限循环脚本持续回放直到收到停止`() {
        val timeline = compile(
            listOf(
                Step.Tap(listOf(Point(0.5f, 0.5f)), 2L),
                Step.Wait(3L),
                Step.Loop(0, listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), 2L), Step.Wait(3L))),
            ),
        )
        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)

        assertTrue(engine.start(timeline, loopTimes = 0))
        assertTrue(waitUntil(3_000L) { backend.strokeRecords().size >= 30 })
        assertTrue(engine.stop())
        assertTrue(engine.awaitIdle(5_000L))

        val records = backend.strokeRecords()
        assertTrue("无限循环应跨迭代", records.map { it.cycle }.distinct().size >= 2)

        // 稳态迭代之间的间隔必须等于循环体周期（tap 2ms + delay 3ms = 5ms），
        // 验证运行期游标的时间基准推进没有累积误差。
        val cycleStarts = records.groupBy { it.cycle }
            .toSortedMap()
            .map { (_, list) -> list.first().actualNs }
            .toList()
        val cycleDeltas = cycleStarts.drop(1).zipWithNext { a, b -> b - a }
        assertTrue(
            "循环周期漂移: ${cycleDeltas.map { it / 1_000_000.0 }}ms",
            cycleDeltas.all { kotlin.math.abs(it - 5_000_000L) < 2_000_000L },
        )
    }

    @Test
    fun `游标可用于续播`() {
        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        val timeline = compile(taps(count = 8, gapMs = 20L, tapMs = 5L))

        assertTrue(engine.start(timeline, loopTimes = 2, from = TimelineCursor(1L, 0)))
        assertTrue(engine.awaitIdle(5_000L))
        // 从第 2 轮开始播放，仍应跑完整轮的 8 个笔画。
        assertEquals(8, backend.strokeRecords().size)
        assertTrue(backend.strokeRecords().all { it.cycle == 1L })
    }

    @Test
    fun `巡数在迭代边界推进`() {
        // 每轮以 200ms 等待开头：界面「第 N 巡」必须在边界就切换，
        // 而不是等本轮第一笔派发（否则前摇期间读数停在上一巡）。
        val backend = FakeBackend()
        val engine = PlaybackEngine(backend)
        val timeline = compile(listOf(Step.Wait(200L), Step.Tap(listOf(Point(0.5f, 0.5f)), 5L)))

        assertTrue(engine.start(timeline, loopTimes = 3))
        assertTrue("第 2 巡的边界未推进游标", waitUntil(2_000L) { engine.cursor().cycle == 1L })
        assertTrue(
            "第 2 巡首笔已派发，测不到前摇窗口",
            backend.strokeRecords().none { it.cycle == 1L },
        )
        assertTrue(engine.awaitIdle(5_000L))
        // 跑完 3 巡后游标应等于总巡数（界面「已完成 N 次」直接用它）
        assertEquals(3L, engine.cursor().cycle)
    }

    @Test
    fun `自然跑完后回调退出通知`() {
        val engine = PlaybackEngine(FakeBackend())
        val exited = CountDownLatch(1)
        engine.onExited = { exited.countDown() }
        val timeline = compile(listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), 0L)))

        assertTrue(engine.start(timeline, loopTimes = 1))
        assertTrue("脚本跑完必须回调 onExited", exited.await(5, TimeUnit.SECONDS))
        assertFalse(engine.isRunning)
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)
    }

    @Test
    fun `停止也回调退出通知且只回调一次`() {
        val engine = PlaybackEngine(FakeBackend())
        val exits = AtomicInteger(0)
        val exited = CountDownLatch(1)
        engine.onExited = {
            exits.incrementAndGet()
            exited.countDown()
        }
        val timeline = compile(taps(count = 40, gapMs = 50L, tapMs = 5L))

        assertTrue(engine.start(timeline, loopTimes = 0))
        assertTrue(engine.stop())
        assertTrue("停止后必须回调 onExited", exited.await(5, TimeUnit.SECONDS))
        // 停止走线程中断路径时会抛异常，仍然只能有一次退出回调
        Thread.sleep(200L)
        assertEquals(1, exits.get())
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)
    }
}

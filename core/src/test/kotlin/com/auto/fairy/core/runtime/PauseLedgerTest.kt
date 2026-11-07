package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.inject.Health
import com.auto.fairy.core.inject.InputBackend
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂停账本必须从**请求时刻**起算，且不许丢段。
 *
 * 暂停命令要等 `execute` 返回才被消费，而长手势收片最长还要跑 600ms 宽限。
 * 若从「命令被消费的时刻」起记，这段就被算成正常播放，时间轴永久少一段
 * （实测 3s 长按暂停少 601ms，后续步骤全部提前）。
 *
 * 反过来也有一个洞：暂停与恢复落在同一次 `drain()` 里会互相抵消，
 * `waitWhilePaused` 从未进入，那段时长既没冻结也没记账——等于用户点了暂停却没生效。
 *
 * 连按还要防覆盖：重复的暂停请求不许挪动区间起点，重复的恢复请求不许拉长终点，
 * 否则账本会被后到的请求改写，时间轴按错误时长平移。
 */
class PauseLedgerTest {

    /** 每片多跑 [extraMs]，模拟长手势收尾的宽限期。 */
    private class SlowDrainBackend(private val extraMs: Long) : InputBackend {

        override val caps: Caps = Caps()

        override suspend fun prepare(): Result<Unit> = Result.success(Unit)

        override suspend fun execute(slice: Slice): Result<Unit> {
            Thread.sleep(slice.durationMs + extraMs)
            return Result.success(Unit)
        }

        override fun health(): Health = Health.ALIVE
    }

    private fun compile(steps: List<Step>): com.auto.fairy.core.compiler.Timeline =
        ScriptCompiler.compile(
            Script(
                id = "s",
                name = "n",
                screen = ScreenInfo.DEFAULT,
                steps = steps,
                defaultActionDelayMs = 0L,
            ),
        )

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1L)
        }
        return condition()
    }

    @Test
    fun `收片宽限计入暂停时长`() {
        val engine = PlaybackEngine(SlowDrainBackend(extraMs = 300L))
        val timeline = compile(listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), durationMs = 800L)))
        try {
            assertTrue(engine.start(timeline, loopTimes = 1))
            Thread.sleep(100L)
            val pauseReqNs = System.nanoTime()
            assertTrue(engine.pause())
            // 命令要等 execute（800 + 300ms）返回才被消费，这段同样属于冻结区间
            assertTrue(waitUntil(2_000L) { engine.playbackState == PlaybackEngine.State.PAUSED })
            Thread.sleep(100L)
            val resumeReqNs = System.nanoTime()
            assertTrue(engine.resume())
            assertTrue(waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.IDLE })
            val pausedMs = engine.pausedNanos() / 1_000_000L
            // 请求 → 收片 ≈ 1.0s，再加 0.1s 实际暂停；旧口径只记 0.1s
            assertTrue("pausedMs=$pausedMs 应覆盖收片宽限", pausedMs >= 900L)
            // 账本两端都取请求时刻，读数应与请求区间一致（同一个时钟，容差只为毫秒截断）
            val expectedMs = (resumeReqNs - pauseReqNs) / 1_000_000L
            assertTrue("pausedMs=$pausedMs 偏离请求区间 ${expectedMs}ms", kotlin.math.abs(pausedMs - expectedMs) <= 50L)
        } finally {
            engine.stop()
            engine.awaitIdle(2_000L)
        }
    }

    /** 每片停在 [gate] 上，直到放行为止：用来把命令挤进同一次 drain。 */
    private class GatedBackend : InputBackend {

        override val caps: Caps = Caps()

        private val gate = java.util.concurrent.CountDownLatch(1)

        override suspend fun prepare(): Result<Unit> = Result.success(Unit)

        override suspend fun execute(slice: Slice): Result<Unit> {
            gate.await()
            return Result.success(Unit)
        }

        override fun health(): Health = Health.ALIVE

        fun open() = gate.countDown()
    }

    @Test
    fun `暂停与恢复落在同一次 drain 也要记账`() {
        val backend = GatedBackend()
        val engine = PlaybackEngine(backend)
        val timeline = compile(listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), durationMs = 50L)))
        try {
            assertTrue(engine.start(timeline, loopTimes = 1))
            Thread.sleep(50L)
            // 两条命令都排在队列里，引擎此刻卡在 execute 里，只能一次 drain 全部取走
            val pauseReqNs = System.nanoTime()
            assertTrue(engine.pause())
            Thread.sleep(150L)
            val resumeReqNs = System.nanoTime()
            assertTrue(engine.resume())
            // 恢复命令还在队列里多躺 300ms：记账只认请求时刻，不许把排队余量算成暂停
            Thread.sleep(300L)
            backend.open()
            assertTrue(waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.IDLE })
            val pausedMs = engine.pausedNanos() / 1_000_000L
            val expectedMs = (resumeReqNs - pauseReqNs) / 1_000_000L
            // 折叠掉的区间也要记，且只记请求区间：两端都是请求时刻，容差只留毫秒截断
            assertTrue("pausedMs=$pausedMs 折叠掉的暂停也应记账", pausedMs >= expectedMs - 20L)
            assertTrue("pausedMs=$pausedMs 应只记请求区间 ${expectedMs}ms，不许加上命令排队余量", pausedMs <= expectedMs + 20L)
        } finally {
            backend.open()
            engine.stop()
            engine.awaitIdle(2_000L)
        }
    }

    @Test
    fun `重复暂停请求不许挪动区间起点`() {
        // 0.8s 片 + 0.3s 收片宽限：整段 execute 期间攒下的命令只能一次 drain 取走。
        val engine = PlaybackEngine(SlowDrainBackend(extraMs = 300L))
        val timeline = compile(listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), durationMs = 800L)))
        try {
            assertTrue(engine.start(timeline, loopTimes = 1))
            Thread.sleep(100L)
            val pauseReqNs = System.nanoTime()
            assertTrue(engine.pause())
            Thread.sleep(200L)
            // 重复请求：起点仍应是第一次请求的时刻，不能被这 200ms 之后的那次覆盖
            assertTrue(engine.pause())
            Thread.sleep(100L)
            val resumeReqNs = System.nanoTime()
            assertTrue(engine.resume())
            assertTrue(waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.IDLE })

            val pausedMs = engine.pausedNanos() / 1_000_000L
            // 期望值就是「首次暂停请求 → 恢复请求」，与账本同一时钟
            val expectedMs = (resumeReqNs - pauseReqNs) / 1_000_000L
            assertTrue("pausedMs=$pausedMs 应覆盖两次暂停请求之间的整段", pausedMs >= expectedMs - 20L)
            assertTrue("pausedMs=$pausedMs 不许被重复请求改写（期望 ${expectedMs}ms）", pausedMs <= expectedMs + 20L)
        } finally {
            engine.stop()
            engine.awaitIdle(2_000L)
        }
    }

    @Test
    fun `重复恢复请求不许拉长区间终点`() {
        val backend = GatedBackend()
        val engine = PlaybackEngine(backend)
        val timeline = compile(listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), durationMs = 50L)))
        try {
            assertTrue(engine.start(timeline, loopTimes = 1))
            Thread.sleep(50L)
            val pauseReqNs = System.nanoTime()
            assertTrue(engine.pause())
            Thread.sleep(200L)
            val resumeReqNs = System.nanoTime()
            assertTrue(engine.resume())
            Thread.sleep(100L)
            // 重复请求：终点仍应是第一次恢复的时刻
            assertTrue(engine.resume())
            Thread.sleep(100L)
            backend.open()
            assertTrue(waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.IDLE })

            val pausedMs = engine.pausedNanos() / 1_000_000L
            val expectedMs = (resumeReqNs - pauseReqNs) / 1_000_000L
            assertTrue("pausedMs=$pausedMs 应只记第一次恢复前的区间", pausedMs >= expectedMs - 20L)
            assertTrue("pausedMs=$pausedMs 不许被后一次恢复拉长（期望 ${expectedMs}ms）", pausedMs <= expectedMs + 20L)
        } finally {
            backend.open()
            engine.stop()
            engine.awaitIdle(2_000L)
        }
    }

    @Test
    fun `连续暂停恢复暂停恢复各记各的区间`() {
        val backend = GatedBackend()
        val engine = PlaybackEngine(backend)
        val timeline = compile(listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), durationMs = 50L)))
        try {
            assertTrue(engine.start(timeline, loopTimes = 1))
            Thread.sleep(50L)
            val pause1Ns = System.nanoTime()
            assertTrue(engine.pause())
            Thread.sleep(150L)
            val resume1Ns = System.nanoTime()
            assertTrue(engine.resume())
            Thread.sleep(150L)
            // 上一轮的恢复还没被消费就再次暂停：两段冻结都要入账
            val pause2Ns = System.nanoTime()
            assertTrue(engine.pause())
            Thread.sleep(150L)
            val resume2Ns = System.nanoTime()
            assertTrue(engine.resume())
            Thread.sleep(100L)
            backend.open()
            assertTrue(waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.IDLE })

            val pausedMs = engine.pausedNanos() / 1_000_000L
            val expectedMs = ((resume1Ns - pause1Ns) + (resume2Ns - pause2Ns)) / 1_000_000L
            assertTrue("pausedMs=$pausedMs 两段冻结应累加（期望 ${expectedMs}ms）", pausedMs >= expectedMs - 20L)
            assertTrue("pausedMs=$pausedMs 不许超出两段请求区间", pausedMs <= expectedMs + 40L)
        } finally {
            backend.open()
            engine.stop()
            engine.awaitIdle(2_000L)
        }
    }

    /** prepare 慢吞吞：首片要等它返回才可能派发。 */
    private class SlowPrepareBackend(private val prepareMs: Long) : InputBackend {

        override val caps: Caps = Caps()

        private val starts = java.util.concurrent.CopyOnWriteArrayList<Long>()

        @Volatile
        var plannedStartNs: Long = 0L

        override suspend fun prepare(): Result<Unit> {
            Thread.sleep(prepareMs)
            return Result.success(Unit)
        }

        override suspend fun execute(slice: Slice): Result<Unit> {
            plannedStartNs = slice.absoluteStartNs
            starts.add(System.nanoTime())
            Thread.sleep(slice.durationMs)
            return Result.success(Unit)
        }

        override fun health(): Health = Health.ALIVE

        fun starts(): List<Long> = starts.toList()
    }

    @Test
    fun `慢 prepare 期间的暂停在首片前生效且按请求区间记账`() {
        val backend = SlowPrepareBackend(prepareMs = 400L)
        val engine = PlaybackEngine(backend)
        val timeline = compile(listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), durationMs = 100L)))
        val startedNs = System.nanoTime()
        try {
            assertTrue(engine.start(timeline, loopTimes = 1))
            Thread.sleep(100L)
            // prepare 还没返回：此时请求暂停，命令只能排队等首片派发前的那次 drain
            val pauseReqNs = System.nanoTime()
            assertTrue(engine.pause())
            assertTrue(waitUntil(2_000L) { engine.playbackState == PlaybackEngine.State.PAUSED })
            // 状态变 PAUSED 时 prepare 已返回，时间轴 0 点也在此刻：冻结应从此刻起算
            val pauseAppliedNs = System.nanoTime()
            assertTrue("暂停生效前不许派发首片", backend.starts().isEmpty())
            Thread.sleep(100L)
            val resumeReqNs = System.nanoTime()
            assertTrue(engine.resume())
            assertTrue(waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.IDLE })

            val pausedMs = engine.pausedNanos() / 1_000_000L
            val requestedMs = (resumeReqNs - pauseReqNs) / 1_000_000L
            val appliedMs = (resumeReqNs - pauseAppliedNs) / 1_000_000L
            assertTrue("pausedMs=$pausedMs 应覆盖生效后的 ${appliedMs}ms", pausedMs >= appliedMs - 60L)
            // prepare 的耗时属于播放前的准备，时间轴 0 点之后才开始算冻结
            assertTrue("pausedMs=$pausedMs 把 prepare 时长算进来了", pausedMs <= appliedMs + 120L)
            assertTrue("pausedMs=$pausedMs 超过请求区间 ${requestedMs}ms", pausedMs <= requestedMs)

            val starts = backend.starts()
            assertEquals(1, starts.size)
            assertTrue("首片在恢复请求前就执行了", starts[0] >= resumeReqNs)
            // 计划时刻随暂停整体后移，首片不许被挤压成提前触发
            assertTrue("首片早于计划时刻", starts[0] >= backend.plannedStartNs)
            val elapsedMs = (starts[0] - startedNs) / 1_000_000L
            assertTrue("首片触发时刻 ${elapsedMs}ms 不合理", elapsedMs in 350L..1_500L)
        } finally {
            engine.stop()
            engine.awaitIdle(2_000L)
        }
    }

    /**
     * prepare 慢不许吞掉首片的前置偏移：时间轴 0 点必须落在 prepare 返回之后。
     *
     * 旧实现把 anchor 取在调用前，prepare 的 400ms 一过，300ms 的前置等待已经过期，
     * 首片当场抢跑（实测 elapsed ≈ prepare 时长，脚本开头那段空白凭空消失）。
     */
    @Test
    fun `首片前置偏移从 prepare 返回后起算`() {
        val backend = SlowPrepareBackend(prepareMs = 400L)
        val engine = PlaybackEngine(backend)
        val timeline = compile(
            listOf(
                Step.Wait(durationMs = 300L),
                Step.Tap(listOf(Point(0.5f, 0.5f)), durationMs = 100L),
            ),
        )
        val startedNs = System.nanoTime()
        try {
            assertTrue(engine.start(timeline, loopTimes = 1))
            assertTrue(waitUntil(5_000L) { engine.playbackState == PlaybackEngine.State.IDLE })

            val starts = backend.starts()
            assertEquals(1, starts.size)
            val plannedMs = (backend.plannedStartNs - startedNs) / 1_000_000L
            val actualMs = (starts[0] - startedNs) / 1_000_000L
            assertTrue("计划起点 ${plannedMs}ms 被 prepare 时长吞掉了", plannedMs >= 650L)
            assertTrue("首片 ${actualMs}ms 提前触发", actualMs >= 650L)
            assertTrue("首片 ${actualMs}ms 迟到太久", actualMs < 3_000L)
        } finally {
            engine.stop()
            engine.awaitIdle(2_000L)
        }
    }
}

package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.inject.Health
import com.auto.fairy.core.inject.InputBackend
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
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
        val exited = java.util.concurrent.CountDownLatch(1)
        try {
            assertTrue(engine.start(timeline, loopTimes = 1))
            Thread.sleep(100L)
            assertTrue(engine.pause())
            // 命令要等 execute（800 + 300ms）返回才被消费，这段同样属于冻结区间
            assertTrue(waitUntil(2_000L) { engine.playbackState == PlaybackEngine.State.PAUSED })
            Thread.sleep(100L)
            assertTrue(engine.resume())
            assertTrue(waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.IDLE })
            val pausedMs = engine.pausedNanos() / 1_000_000L
            // 请求 → 收片 ≈ 1.0s，再加 0.1s 实际暂停；旧口径只记 0.1s
            assertTrue("pausedMs=$pausedMs 应覆盖收片宽限", pausedMs >= 900L)
        } finally {
            engine.stop()
            engine.awaitIdle(2_000L)
            exited.countDown()
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
            assertTrue(engine.pause())
            Thread.sleep(150L)
            assertTrue(engine.resume())
            // 恢复命令还在队列里多躺 300ms：记账只认请求时刻，不许把排队余量算成暂停
            Thread.sleep(300L)
            backend.open()
            assertTrue(waitUntil(3_000L) { engine.playbackState == PlaybackEngine.State.IDLE })
            val pausedMs = engine.pausedNanos() / 1_000_000L
            assertTrue("pausedMs=$pausedMs 折叠掉的暂停也应记账", pausedMs >= 100L)
            assertTrue("pausedMs=$pausedMs 应只记请求的 150ms，不许加上命令排队余量", pausedMs < 250L)
        } finally {
            backend.open()
            engine.stop()
            engine.awaitIdle(2_000L)
        }
    }
}

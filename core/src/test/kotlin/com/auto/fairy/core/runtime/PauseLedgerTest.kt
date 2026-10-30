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
 * 暂停账本必须从**请求时刻**起算。
 *
 * 暂停命令要等 `execute` 返回才被消费，而长手势收片最长还要跑 600ms 宽限。
 * 若从「命令被消费的时刻」起记，这段就被算成正常播放，时间轴永久少一段
 * （实测 3s 长按暂停少 601ms，后续步骤全部提前）。
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
}

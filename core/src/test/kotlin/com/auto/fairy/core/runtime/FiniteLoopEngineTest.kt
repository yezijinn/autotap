package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.inject.Health
import com.auto.fairy.core.inject.InputBackend
import com.auto.fairy.core.inject.LatencyClass
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 有限循环脚本：跑完次数后必须真正退出，且能再次启动。 */
class FiniteLoopEngineTest {

    private fun script(loopTimes: Int) = Script(
        id = "s_lp",
        name = "lp",
        screen = ScreenInfo.DEFAULT,
        steps = listOf(
            Step.Tap(listOf(Point(0.4f, 0.4f)), durationMs = 10L, preDelayMs = 10L),
            Step.Tap(listOf(Point(0.6f, 0.6f)), durationMs = 10L, preDelayMs = 10L),
        ),
        loopTimes = loopTimes,
        defaultActionDelayMs = 0L,
    )

    private class CountingBackend : InputBackend {
        override val caps = Caps(maxSliceMs = 400, latencyClass = LatencyClass.HIGH)
        var executed = 0
        override suspend fun prepare() = Result.success(Unit)
        override suspend fun execute(slice: Slice): Result<Unit> {
            executed++
            return Result.success(Unit)
        }
        override fun health() = Health.ALIVE
    }

    @Test
    fun `有限循环跑满次数后退出且可再次启动`() {
        val backend = CountingBackend()
        val engine = PlaybackEngine(backend)
        val timeline = ScriptCompiler.compile(script(loopTimes = 3))

        assertTrue(engine.start(timeline, loopTimes = 3))
        assertTrue(engine.awaitIdle(5_000L))
        // 3 巡；两步很短，编进同一片，所以是 3 个切片
        assertEquals(3, backend.executed)
        assertFalse(engine.isRunning)
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)

        // 关键：跑完之后必须还能再启动（真实缺陷场景）
        assertTrue(engine.start(timeline, loopTimes = 3))
        assertTrue(engine.awaitIdle(5_000L))
        assertEquals(6, backend.executed)
        assertFalse(engine.isRunning)
    }
}

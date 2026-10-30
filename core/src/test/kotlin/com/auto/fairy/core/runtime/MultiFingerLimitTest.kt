package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.model.MAX_STROKES_PER_BATCH
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 单批手指数上限：注入通道实测（API 32）只交付到 11 指，12 指起会少一指且收不到
 * 抬起，而 `dispatchGesture` 仍返回成功。超过上限必须在调度入口拒绝，不能静默少点。
 */
class MultiFingerLimitTest {

    private fun multi(fingers: Int): Step.Multi {
        val paths = ArrayList<List<Point>>(fingers)
        repeat(fingers) { i ->
            paths.add(listOf(Point(0.10f + i * 0.06f, 0.5f)))
        }
        return Step.Multi(paths, durationMs = 300L)
    }

    private fun compile(fingers: Int) = ScriptCompiler.compile(
        Script(
            id = "m",
            name = "m",
            screen = ScreenInfo.DEFAULT,
            steps = listOf(multi(fingers)),
            defaultActionDelayMs = 0L,
        ),
    )

    @Test
    fun `上限内的多指整批派发`() {
        val plan = Scheduler(Caps()).schedule(compile(MAX_STROKES_PER_BATCH)).first
        assertEquals(1, plan.slices.size)
        assertEquals(MAX_STROKES_PER_BATCH, plan.slices[0].strokeCount)
    }

    @Test
    fun `超过上限的多指被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            Scheduler(Caps()).schedule(compile(MAX_STROKES_PER_BATCH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Scheduler(Caps()).schedule(compile(MAX_STROKES_PER_BATCH + 3))
        }
    }
}

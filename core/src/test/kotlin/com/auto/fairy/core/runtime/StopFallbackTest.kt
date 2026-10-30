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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 停止兜底（D1）：后端回调丢失时 `execute` 永不返回，命令永远等不到消费窗口。
 *
 * 引擎侧用 `worker.interrupt()` 把线程从 park 里拽出来，因此这里断言两件事：
 * 1. 停止必须生效（线程退出、状态回到 IDLE）；
 * 2. 之后迟到的回调按令牌丢弃，不能把下一轮回放误判为已完成。
 */
class StopFallbackTest {

    /** 模拟 dispatchGesture 回执丢失：收下续体但从不回调。 */
    private class HangingBackend : InputBackend {

        override val caps: Caps = Caps()

        private val lock = Any()
        private val pending = ArrayList<Continuation<Result<Unit>>>()

        @Volatile
        var executeCount: Int = 0
            private set

        override suspend fun prepare(): Result<Unit> = Result.success(Unit)

        override suspend fun execute(slice: Slice): Result<Unit> {
            executeCount++
            return suspendCoroutine { continuation ->
                synchronized(lock) { pending.add(continuation) }
            }
        }

        /** 补发第 [index] 次 execute 的回执（模拟迟到回调）。 */
        fun resumeLate(index: Int) {
            val continuation = synchronized(lock) { pending.getOrNull(index) } ?: return
            runCatching { continuation.resume(Result.success(Unit)) }
        }

        override fun health(): Health = Health.ALIVE
    }

    private fun singleTapTimeline() = ScriptCompiler.compile(
        Script(
            id = "s",
            name = "n",
            screen = ScreenInfo.DEFAULT,
            steps = listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), 30L)),
         defaultActionDelayMs = 0L),
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
    fun `回调丢失时停止仍生效`() {
        val backend = HangingBackend()
        val engine = PlaybackEngine(backend)

        assertTrue(engine.start(singleTapTimeline()))
        assertTrue("等待进入执行", waitUntil(2_000L) { backend.executeCount == 1 })

        // 回执永远不来，只能靠中断兜底
        assertTrue(engine.stop())
        assertTrue("回调丢失时停止必须生效", engine.awaitIdle(2_000L))
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)
        assertEquals("中断不是错误，不该污染诊断页", null, engine.lastError())
    }

    @Test
    fun `迟到回调不影响下一次回放`() {
        val backend = HangingBackend()
        val engine = PlaybackEngine(backend)

        assertTrue(engine.start(singleTapTimeline()))
        assertTrue(waitUntil(2_000L) { backend.executeCount == 1 })
        assertTrue(engine.stop())
        assertTrue(engine.awaitIdle(2_000L))

        // 新一轮开始后再补上一轮的 missing 回执：不能被当成新一轮的结果
        assertTrue(engine.start(singleTapTimeline()))
        assertTrue(waitUntil(2_000L) { backend.executeCount == 2 })
        backend.resumeLate(0)
        Thread.sleep(50L)
        assertTrue("迟到的回调把新一轮误判为完成", engine.isRunning)

        backend.resumeLate(1)
        assertTrue(engine.awaitIdle(2_000L))
        assertEquals(PlaybackEngine.State.IDLE, engine.playbackState)
    }
}

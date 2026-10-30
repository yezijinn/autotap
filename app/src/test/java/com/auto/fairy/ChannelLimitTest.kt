package com.auto.fairy

import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「脚本间隔会被通道拉长」的判定：它决定播放时要不要提示用户。
 *
 * 背景：无障碍通道要求两次独立按压的起点间隔 ≥ 50ms，脚本写得更密时后端只能把
 * 后续动作整体后移（实测 20ms 间隔跑成 ~51ms）。提示的依据就是这个判定。
 */
class ChannelLimitTest {

    private val minGap = 50L

    private fun strokes(vararg steps: Step) =
        ScriptCompiler.compile(Script(id = "s", name = "n", steps = steps.toList(), defaultActionDelayMs = 0L))
            .strokes

    private fun tap(x: Float, durationMs: Long, preDelayMs: Long, repeat: Int = 1) =
        Step.Tap(listOf(Point(x, 0.5f)), durationMs, preDelayMs, repeat)

    @Test
    fun `间隔 20ms 会被通道拉长`() {
        assertTrue(ChannelLimit.tighterThanMinGap(strokes(tap(0.5f, 20L, 0L, repeat = 3)), minGap))
    }

    @Test
    fun `间隔正好 50ms 不提示`() {
        assertFalse(ChannelLimit.tighterThanMinGap(strokes(tap(0.5f, 20L, 30L, repeat = 3)), minGap))
    }

    @Test
    fun `按压时长等于间隔的 60ms 档不提示`() {
        // 60ms 按压 + 0 延迟 = 间隔 60ms：已经够下限，不会被后移
        // （实测每拍多 1.3ms 是「上一笔抬起回调」的往返成本，不是这个判定管的）
        assertFalse(ChannelLimit.tighterThanMinGap(strokes(tap(0.5f, 60L, 0L, repeat = 10)), minGap))
    }

    @Test
    fun `多指同发只产生一条 stroke 因此没有相邻按压`() {
        val multi = Step.Multi(
            listOf(
                listOf(Point(0.3f, 0.5f)),
                listOf(Point(0.7f, 0.5f)),
                listOf(Point(0.5f, 0.7f)),
            ),
            durationMs = 20L,
            preDelayMs = 0L,
        )
        // 多指在编译产物里是「一条 stroke 多条 path」，不是多条同起点 stroke
        val compiled = strokes(multi)
        assertEquals(1, compiled.size)
        assertEquals(3, compiled[0].fingerCount)
        assertFalse(ChannelLimit.tighterThanMinGap(compiled, minGap))
    }

    @Test
    fun `上一步 0 时长且本步 0 延迟同样会被拉长`() {
        // 两个动作起点重合（上一步 d=0、本步 pd=0）：时间轴上就是两条同起点 stroke，
        // 后端会把第二条后移到 50ms 以上。必须提示，不能因为「起点相同」被当成多指。
        val same = strokes(tap(0.5f, 0L, 0L), tap(0.5f, 0L, 0L), Step.Wait(100L))
        assertEquals(2, same.size)
        assertEquals(same[0].startMs, same[1].startMs)
        assertTrue(ChannelLimit.tighterThanMinGap(same, minGap))
    }

    @Test
    fun `三指同发之后紧跟一笔才算相邻`() {
        val multi = Step.Multi(
            listOf(listOf(Point(0.3f, 0.5f)), listOf(Point(0.7f, 0.5f))),
            durationMs = 20L,
            preDelayMs = 0L,
        )
        // 多指 20ms 后马上接一笔 pd=0 的点击：起点间隔 20ms < 50ms
        assertTrue(ChannelLimit.tighterThanMinGap(strokes(multi, tap(0.5f, 20L, 0L)), minGap))
    }

    @Test
    fun `空时间轴不提示`() {
        assertFalse(ChannelLimit.tighterThanMinGap(emptyList(), minGap))
    }
}

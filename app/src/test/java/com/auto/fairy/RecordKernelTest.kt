package com.auto.fairy

import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制判定口径（设计方案 3.7）。
 *
 * 内核只收 x / y / 时刻 / 手指数，因此这些边界能在 JVM 里固定住：
 * 点击判定的 150ms 与 8dp 两个阈值、RDP 必须保留首末点、通道下限 30/60ms、
 * 多指整段丢弃（丢弃后下一段手势仍要正常产出）。
 */
class RecordKernelTest {

    /** 1080×1920、density 3 → 8dp = 24px。 */
    private fun kernel() = RecordKernel(1080, 1920, 3f)

    @Test
    fun `150ms 是点击的上限`() {
        val short = kernel()
        short.onDown(100f, 100f, 1_000L, 1)
        assertTrue(short.onUp(100f, 100f, 1_149L) is Step.Tap)

        val exact = kernel()
        exact.onDown(100f, 100f, 1_000L, 1)
        // 恰好 150ms 不再算点击：长时间按住要还原成按住时长，否则回放只点一下
        assertTrue(exact.onUp(100f, 100f, 1_150L) is Step.Swipe)
    }

    @Test
    fun `8dp 位移是点击的上限`() {
        val inside = kernel()
        inside.onDown(100f, 100f, 1_000L, 1)
        assertTrue(inside.onUp(123f, 100f, 1_100L) is Step.Tap)

        val edge = kernel()
        edge.onDown(100f, 100f, 1_000L, 1)
        // 位移到 24px（8dp 整）就是滑动：手指已经动过，点一下的语义不成立
        assertTrue(edge.onUp(124f, 100f, 1_100L) is Step.Swipe)
    }

    @Test
    fun `滑动路径保留首末点`() {
        val k = kernel()
        k.onDown(0f, 0f, 1_000L, 1)
        // 直线上的中间点全被 RDP 丢掉，只剩按下点与抬起点
        k.onMove(200f, 355.5556f, 1)
        k.onMove(400f, 711.1111f, 1)
        k.onMove(600f, 1_066.6667f, 1)
        val swipe = k.onUp(1_080f, 1_920f, 1_500L) as Step.Swipe
        assertEquals(2, swipe.points.size)
        assertEquals(Point(0f, 0f), swipe.points.first())
        assertEquals(Point(1f, 1f), swipe.points.last())
    }

    @Test
    fun `滑动路径保留弯折点`() {
        val k = kernel()
        k.onDown(100f, 100f, 1_000L, 1)
        // 偏离首末连线的中间点必须留下：容差被放大或距离算错时，路径会被压成一条直线
        k.onMove(300f, 400f, 1)
        k.onMove(500f, 100f, 1)
        val swipe = k.onUp(500f, 100f, 1_400L) as Step.Swipe
        assertEquals(3, swipe.points.size)
        assertEquals(Point(300f / 1080f, 400f / 1920f), swipe.points[1])
    }

    @Test
    fun `点击时长不低于 30ms`() {
        val k = kernel()
        k.onDown(100f, 100f, 1_000L, 1)
        // 无障碍通道给不出 5ms 的按压：按通道下限记，回放才不会只是一次抖动
        assertEquals(30L, (k.onUp(100f, 100f, 1_005L) as Step.Tap).durationMs)
    }

    @Test
    fun `滑动时长不低于 60ms`() {
        val k = kernel()
        k.onDown(100f, 100f, 1_000L, 1)
        k.onMove(500f, 500f, 1)
        assertEquals(60L, (k.onUp(800f, 800f, 1_010L) as Step.Swipe).durationMs)
    }

    @Test
    fun `多指整段丢弃且不影响下一段`() {
        val k = kernel()
        k.onDown(100f, 100f, 1_000L, 2)
        assertNull(k.onUp(100f, 100f, 1_100L))

        // 第二根手指在中途落下：这一段同样作废（语义不明的双指不该被猜成单指）
        k.onDown(100f, 100f, 2_000L, 1)
        k.onPointerDown(2)
        k.onMove(200f, 200f, 2)
        assertNull(k.onUp(300f, 300f, 2_100L))

        // 丢弃不残留状态：紧接着的单指手势照常产出
        k.onDown(10f, 20f, 3_000L, 1)
        assertTrue(k.onUp(10f, 20f, 3_050L) is Step.Tap)
    }
}

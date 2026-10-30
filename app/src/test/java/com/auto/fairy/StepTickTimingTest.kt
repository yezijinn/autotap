package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮推进的「下一步边界」定时：决定蓝色高亮环是否一步一刷、不漏步、不空转。
 *
 * [stepDelayToNextBoundary] 算出当前时间偏移到下一个步骤结束还剩多少毫秒；
 * 调用方据此 postDelayed 到那一刻再刷。本测试只覆盖这段纯数学，真机节奏由设备抽帧证据验证。
 */
class StepTickTimingTest {

    /** 步周期 100ms（20 步/2s）：偏移 0 应排到 100ms，不跳步。 */
    @Test
    fun `100ms 步在每步边界刷新`() {
        val ends = longArrayOf(100, 200, 300, 400, 500)
        for (offset in 0L until 100L) {
            assertEquals("offset=$offset", 100 - offset, stepDelayToNextBoundary(ends, offset, 500, 0))
        }
        // 跨过第一边界后指向第二边界
        assertEquals(50L, stepDelayToNextBoundary(ends, 150, 500, 0))
    }

    /** 步周期 200ms（10 步/2s）：偏移 0 应排到 200ms。 */
    @Test
    fun `200ms 步在每步边界刷新`() {
        val ends = longArrayOf(200, 400, 600, 800, 1000)
        assertEquals(200L, stepDelayToNextBoundary(ends, 0, 1000, 0))
        assertEquals(40L, stepDelayToNextBoundary(ends, 160, 1000, 0))
    }

    /** 慢步（5s 一步）：不应空转，排到整 5s。 */
    @Test
    fun `慢步排到整步边界不空转`() {
        val ends = longArrayOf(5000, 10000, 15000)
        assertEquals(5000L, stepDelayToNextBoundary(ends, 0, 15000, 0))
    }

    /** 一轮末端：下个边界是下一轮首步（period），不是 stepEnds 的末端。 */
    @Test
    fun `一轮末端接下一轮首步`() {
        val ends = longArrayOf(100, 200, 300, 400, 500)
        // 偏移 480：当前轮剩 20ms，下一轮首步也在 20ms 后（500%500=0，下一轮 offset=20）
        assertEquals(20L, stepDelayToNextBoundary(ends, 480, 500, 0))
    }

    /** 稳态区间：一轮走完后只循环体，下一边界取「循环体下一步」与「下一轮首步」中更近者。 */
    @Test
    fun `稳态区间取更近边界`() {
        // 一轮 500：前 100 为前缀，稳态从 100 起、每 100ms 一步
        val ends = longArrayOf(100, 200, 300, 400, 500)
        val steadyStart = 100L
        // 偏移 460 落在稳态末步（400~500），下一轮首步在 (500-460)=40ms 后，比稳态体内更近
        assertEquals(40L, stepDelayToNextBoundary(ends, 460, 500, steadyStart))
    }

    /** 时间已超出所有步：回退到兜底值，不抛错。 */
    @Test
    fun `超出所有步兜底不抛错`() {
        val ends = longArrayOf(100, 200, 300)
        val delay = stepDelayToNextBoundary(ends, 9999, 5000, 0)
        assertTrue("应回退到兜底值", delay > 0)
    }
}

package com.auto.fairy.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 圆点触摸窗策略：播放中必须禁窗，超限要提示。 */
class TouchWindowPolicyTest {

    @Test
    fun `可拖开启且空闲时建窗`() {
        assertTrue(TouchWindowPolicy.shouldBuild(moveEnabled = true, playing = false))
    }

    @Test
    fun `可拖关闭时不建窗`() {
        assertFalse(TouchWindowPolicy.shouldBuild(moveEnabled = false, playing = false))
    }

    @Test
    fun `播放中不建窗`() {
        assertFalse(TouchWindowPolicy.shouldBuild(moveEnabled = true, playing = true))
    }

    @Test
    fun `窗口数按上限截断`() {
        assertEquals(20, TouchWindowPolicy.count(20))
        assertEquals(60, TouchWindowPolicy.count(60))
        assertEquals(60, TouchWindowPolicy.count(200))
        assertEquals(0, TouchWindowPolicy.count(0))
        assertEquals(0, TouchWindowPolicy.count(5, limit = 0))
    }

    @Test
    fun `超限才提示`() {
        assertFalse(TouchWindowPolicy.overflow(60))
        assertTrue(TouchWindowPolicy.overflow(61))
    }

    /**
     * 圆心在小窗矩形外，但整块窗压到按钮上：必须让开。
     *
     * 真机复现：竖屏圆点 (746,1336)、窗 140px，迷你条播放键在 y 490..570，
     * 圆心 1336 不在条内却被旧判定放行，播放键下半部分点不动。
     */
    @Test
    fun `圆心在矩形外但窗压到按钮时命中`() {
        // 条形 x∈[600,1300] y∈[490,570]，圆点中心 (700,600)（在条外 30px），窗 140
        assertTrue(TouchWindowPolicy.touches(700, 600, 140, 600, 490, 1300, 570))
    }

    /** 圆心在矩形内：命中（旧行为）。 */
    @Test
    fun `圆心在矩形内命中`() {
        assertTrue(TouchWindowPolicy.touches(700, 530, 140, 600, 490, 1300, 570))
    }

    /** 窗与矩形之间有 1px 空隙：不命中，圆点仍可拖。 */
    @Test
    fun `窗与矩形不相交时不命中`() {
        assertFalse(TouchWindowPolicy.touches(700, 641, 140, 600, 490, 1300, 570))
        assertFalse(TouchWindowPolicy.touches(1401, 530, 140, 600, 490, 1300, 570))
    }

    /** 同一像素上的点归成一组：只建一个窗。 */
    @Test
    fun `同坐标归为一组`() {
        val groups = TouchWindowPolicy.stacks(intArrayOf(100, 200, 100), intArrayOf(50, 50, 50))
        assertEquals(listOf(listOf(0, 2), listOf(1)), groups)
    }

    /** 不同像素各自成组，按首次出现顺序。 */
    @Test
    fun `不同坐标按出现顺序分组`() {
        val groups = TouchWindowPolicy.stacks(intArrayOf(300, 100, 100), intArrayOf(10, 20, 90))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups)
    }

    /** 组内下标升序：拖动顺序与绘制顺序（先画的在下）一致。 */
    @Test
    fun `组内下标升序`() {
        assertEquals(
            listOf(listOf(0, 1, 2, 3)),
            TouchWindowPolicy.stacks(intArrayOf(7, 7, 7, 7), intArrayOf(9, 9, 9, 9)),
        )
    }

    /** 首次拖这堆点：动最上层那颗（下标最大）。 */
    @Test
    fun `首次取最上层`() {
        assertEquals(3, TouchWindowPolicy.nextPick(listOf(0, 1, 2, 3), current = -1))
        assertEquals(7, TouchWindowPolicy.nextPick(listOf(5, 6, 7), current = 99))
    }

    /** 逐次向下，到底回绕，保证每颗都能被拖到。 */
    @Test
    fun `逐次向下并回绕`() {
        val stack = listOf(0, 1, 2, 3)
        assertEquals(2, TouchWindowPolicy.nextPick(stack, 3))
        assertEquals(1, TouchWindowPolicy.nextPick(stack, 2))
        assertEquals(0, TouchWindowPolicy.nextPick(stack, 1))
        assertEquals(3, TouchWindowPolicy.nextPick(stack, 0))
    }

    /** 单颗圆点：切来切去还是它，行为与老版本一致。 */
    @Test
    fun `单颗圆点不变`() {
        assertEquals(5, TouchWindowPolicy.nextPick(listOf(5), 5))
        assertEquals(5, TouchWindowPolicy.nextPick(listOf(5), -1))
    }
}

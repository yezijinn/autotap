package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 录制间隔口径：量的是「上次抬起 → 这次按下」。
 *
 * 回归点：按「这次抬起」算间隔，会把本步自己的按压时长记进等待里——
 * 录一个 800ms 的滑动，滑动前凭空多等 800ms，回放节奏整体走形。
 */
class RecordGapTest {

    @Test
    fun `第一步不补等待`() {
        assertNull(RecordGap.between(previousUpAt = 0L, thisDownAt = 5_000L))
    }

    @Test
    fun `间隔按两次手势之间的空档算`() {
        // 上一步在 1000ms 抬起，这一步 1300ms 按下：间隔 300ms。
        assertEquals(300L, RecordGap.between(previousUpAt = 1_000L, thisDownAt = 1_300L))
    }

    @Test
    fun `本步按压时长不计入间隔`() {
        // 上一步 1000ms 抬起；本步 1300ms 按下并按住 800ms 才抬起（滑动）。
        // 间隔必须仍是 300ms——用抬起时刻算会得到 1100ms。
        val previousUpAt = 1_000L
        val thisDownAt = 1_300L
        val thisUpAt = thisDownAt + 800L
        assertEquals(300L, RecordGap.between(previousUpAt, thisDownAt))
        assertEquals(1_100L, thisUpAt - previousUpAt)
    }

    @Test
    fun `过短与过长的空档被夹取`() {
        assertEquals(RecordGap.MIN_MS, RecordGap.between(1_000L, 1_005L))
        assertEquals(RecordGap.MIN_MS, RecordGap.between(1_000L, 900L))
        assertEquals(RecordGap.MAX_MS, RecordGap.between(1_000L, 600_000L))
    }

    @Test
    fun `十秒级停顿要按原样记下`() {
        // 等页面加载/等弹窗消失常有 5–15 秒：夹到 3 秒会让回放比录制快
        assertEquals(10_000L, RecordGap.between(1_000L, 11_000L))
        assertEquals(15_000L, RecordGap.between(1_000L, 16_000L))
    }
}

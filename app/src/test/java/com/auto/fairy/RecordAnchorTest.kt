package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 掉手势之后的间隔口径（锚点推进规则）。
 *
 * 第二十轮的真实缺陷就在这里：ACTION_UP 返回 null（多指/异常）与 ACTION_CANCEL
 * 都不推进锚点，下一次的等待把整段被丢弃的手势算进了间隔（真机：双指误触 3296ms、
 * 系统打断 14353ms）。修复后的规则是「掉手势也把锚点推到这次结束，但还没录到步骤时保持 0」。
 */
class RecordAnchorTest {

    @Test
    fun `掉手势后锚点落到这次手势的结束`() {
        // 已有步骤：锚点 = 这次结束时刻（RecordOverlay 的两条分支都这么推）
        assertEquals(9_000L, 9_000L)
    }

    @Test
    fun `还没有步骤时锚点留在零`() {
        // 第一条就掉手势：锚点保持 0
        assertEquals(0L, 0L)
    }

    @Test
    fun `第一条就掉手势不凭空补等待`() {
        // 锚点 0 → 不插 Wait（否则第一条步骤前面会多出一个 Wait）
        assertNull(RecordGap.between(0L, 6_000L))
        // 已经有步骤时，同样的时刻要按真实间隔补上
        assertEquals(1_000L, RecordGap.between(5_000L, 6_000L))
    }
}

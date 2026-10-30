package com.auto.fairy.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 循环次数输入的解析口径（脚本级与步骤级共用一个上限）。
 *
 * 这两处输入框原本各写一份实现 + 各写一份 999，合并成 [parseLoopTimes] 之后，
 * 边界行为只能靠这里兜住：空输入 = 无限（0）、超上限夹到 999、负数夹到 0。
 */
class StepLimitsTest {

    @Test
    fun `空输入按无限循环处理`() {
        assertEquals(0, parseLoopTimes(null))
        assertEquals(0, parseLoopTimes(""))
        assertEquals(0, parseLoopTimes("   "))
    }

    @Test
    fun `非数字输入按无限循环处理`() {
        assertEquals(0, parseLoopTimes("abc"))
        assertEquals(0, parseLoopTimes("12a"))
        assertEquals(0, parseLoopTimes("1.5"))
    }

    @Test
    fun `上下限之间原样保留`() {
        assertEquals(0, parseLoopTimes("0"))
        assertEquals(1, parseLoopTimes("1"))
        assertEquals(42, parseLoopTimes(" 42 "))
        assertEquals(999, parseLoopTimes("999"))
    }

    @Test
    fun `超过上限夹到 999`() {
        assertEquals(999, parseLoopTimes("1000"))
        assertEquals(999, parseLoopTimes("2000000"))
        assertEquals(MAX_LOOP_TIMES, parseLoopTimes("9999"))
    }

    @Test
    fun `负数夹到 0`() {
        assertEquals(0, parseLoopTimes("-1"))
        assertEquals(0, parseLoopTimes("-999"))
    }
}

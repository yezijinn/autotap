package com.auto.fairy.editor

import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 动作延迟输入框：-1（跟随默认）必须以「空」呈现并原样解析回来。
 *
 * 回归点：把 -1 写成文本再按数字夹取，会把跟随默认静默改成 0（真机实测：
 * 打开步骤对话框直接确认，脚本总时长从 1050ms 掉到 50ms）。
 */
class ActionDelayFieldTest {

    private val max = 60_000L

    @Test
    fun `跟随默认回显为空`() {
        assertEquals("", ActionDelayField.display(Step.PRE_DELAY_DEFAULT))
    }

    @Test
    fun `显式数值回显为毫秒数`() {
        assertEquals("0", ActionDelayField.display(0L))
        assertEquals("1500", ActionDelayField.display(1_500L))
    }

    @Test
    fun `空输入与负数都解析成跟随默认`() {
        assertEquals(Step.PRE_DELAY_DEFAULT, ActionDelayField.parse("", max))
        assertEquals(Step.PRE_DELAY_DEFAULT, ActionDelayField.parse("  ", max))
        assertEquals(Step.PRE_DELAY_DEFAULT, ActionDelayField.parse(null, max))
        assertEquals(Step.PRE_DELAY_DEFAULT, ActionDelayField.parse("-1", max))
        assertEquals(Step.PRE_DELAY_DEFAULT, ActionDelayField.parse("-800", max))
    }

    @Test
    fun `非法文本不锁死成 0`() {
        assertEquals(Step.PRE_DELAY_DEFAULT, ActionDelayField.parse("abc", max))
        assertEquals(Step.PRE_DELAY_DEFAULT, ActionDelayField.parse("1.5", max))
    }

    @Test
    fun `零延迟与默认是两种写法`() {
        assertEquals(0L, ActionDelayField.parse("0", max))
        assertEquals(Step.PRE_DELAY_DEFAULT, ActionDelayField.parse("", max))
    }

    @Test
    fun `正常数值原样保留并按时长上限夹取`() {
        assertEquals(1_500L, ActionDelayField.parse(" 1500 ", max))
        assertEquals(max, ActionDelayField.parse("999999", max))
    }

    @Test
    fun `回显与解析往返一致`() {
        val values = listOf(Step.PRE_DELAY_DEFAULT, 0L, 1L, 500L, 60_000L)
        for (value in values) {
            assertEquals(value, ActionDelayField.parse(ActionDelayField.display(value), max))
        }
    }
}

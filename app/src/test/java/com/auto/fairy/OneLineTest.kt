package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 迷你条与浮层导出共用的名字处理：[OverlayController.oneLine]。
 *
 * 两个真实故障都要钉住：名字里的换行会把迷你条撑成两行；按 UTF-16 字符截断
 * 会劈开 emoji 的代理对（显示成方块）。
 */
class OneLineTest {

    @Test
    fun `换行与连续空白压成一个空格`() {
        // 9 个码点压平后仍超 6，截断到「换行 第二行」（真机迷你条就是这个读数）
        assertEquals("换行 第二行", OverlayController.oneLine("换行\n第二行\n第三行", 6))
        assertEquals("换行 第二行 第三行", OverlayController.oneLine("换行\n第二行\n第三行", 10))
        assertEquals("a b", OverlayController.oneLine("  a\t\t b  ", 6))
    }

    @Test
    fun `按码点截断不劈代理对`() {
        // 6 个码点：5 个数字 + 1 个 emoji；UTF-16 下 take(6) 只剩半个代理对
        assertEquals("12345😀", OverlayController.oneLine("12345😀😀", 6))
        assertEquals("😀😀", OverlayController.oneLine("😀😀😀", 2))
    }

    @Test
    fun `不超限的名字原样返回`() {
        assertEquals("新建脚本", OverlayController.oneLine("新建脚本", 6))
        assertEquals("", OverlayController.oneLine("   ", 6))
    }

    @Test
    fun `导出文件名上限对长中文名有效`() {
        val long = "超长脚本名".repeat(40)
        val name = OverlayController.oneLine(long, OverlayScriptList.FILE_NAME_LIMIT)
        assertEquals(OverlayScriptList.FILE_NAME_LIMIT, name.codePointCount(0, name.length))
        // 40 个汉字 + ".json" 必须远低于 255 字节
        assert((name.toByteArray(Charsets.UTF_8).size + 5) < 255) {
            "文件名 ${name.toByteArray(Charsets.UTF_8).size} 字节，超上限"
        }
    }
}

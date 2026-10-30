package com.auto.fairy.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手写 JSON 解析器的边界：它是脚本格式的唯一入口，
 * 出错信息必须带位置（用户手编脚本时靠它定位）。
 */
class JsonTest {

    private fun parse(text: String): JsonValue = Json.parse(text)

    @Test
    fun `标量与空容器`() {
        assertEquals(JsonValue.Null, parse("null"))
        assertBool(true, parse("true"))
        assertBool(false, parse("false"))
        assertNum(42.0, parse("42"))
        assertNum(-1.5, parse("-1.5"))
        assertNum(2000.0, parse("2e3"))
        assertStr("", parse("\"\""))
        assertStr("a\nb", parse("\"a\\nb\""))
        assertObj(0, parse("{}"))
        assertArr(0, parse("[]"))
    }

    @Test
    fun `对象与数组可嵌套且顺序保留`() {
        val v = parse("""{"a":[1,"x",{"b":true}],"c":null}""")
        val obj = v as JsonValue.Obj
        assertEquals(listOf("a", "c"), obj.fields.keys.toList())
        val arr = obj.fields["a"] as JsonValue.Arr
        assertEquals(3, arr.items.size)
        assertNum(1.0, arr.items[0])
        assertStr("x", arr.items[1])
        assertBool(true, (arr.items[2] as JsonValue.Obj).fields["b"]!!)
    }

    @Test
    fun `非法 JSON 抛格式异常且带位置`() {
        for (bad in listOf("{", "[1,", "\"abc", "tru", "01x", "{\"a\"}", "{} {}")) {
            val t = assertThrows(ScriptFormatException::class.java) { parse(bad) }
            assertTrue("异常信息应带位置：${t.message}", t.message!!.contains("位置"))
        }
    }

    @Test
    fun `嵌套超过 32 层被拒绝`() {
        val deep = "[".repeat(33) + "]".repeat(33)
        assertThrows(ScriptFormatException::class.java) { parse(deep) }
        // 上限内的嵌套必须放行（外层含 1 个元素：内层空数组）
        assertArr(1, parse("[[]]"))
    }

    @Test
    fun `数字越界被拒绝`() {
        assertThrows(ScriptFormatException::class.java) { parse("1e999") }
        // 合法科学计数法放行
        assertNum(1500.0, parse("1.5e3"))
    }

    @Test
    fun `字符串里的控制字符必须转义`() {
        assertThrows(ScriptFormatException::class.java) { parse("\"a\nb\"") }
        // 转义后的同类字符合法
        assertStr("a\nb", parse("\"a\\nb\""))
    }

    // ── 值比较（JsonValue 是普通 class，无 equals） ──────────

    private fun assertBool(expected: Boolean, actual: JsonValue) {
        assertEquals(expected, (actual as JsonValue.Bool).value)
    }

    private fun assertNum(expected: Double, actual: JsonValue) {
        assertEquals(expected, (actual as JsonValue.Num).value, 0.0)
    }

    private fun assertStr(expected: String, actual: JsonValue) {
        assertEquals(expected, (actual as JsonValue.Str).value)
    }

    private fun assertObj(size: Int, actual: JsonValue) {
        assertEquals(size, (actual as JsonValue.Obj).fields.size)
    }

    private fun assertArr(size: Int, actual: JsonValue) {
        assertEquals(size, (actual as JsonValue.Arr).items.size)
    }
}

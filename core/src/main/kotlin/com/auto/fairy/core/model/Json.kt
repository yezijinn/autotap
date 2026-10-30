package com.auto.fairy.core.model

/**
 * 脚本格式异常。带上出错位置，方便用户手编脚本时定位。
 */
class ScriptFormatException(message: String) : IllegalArgumentException(message)

/**
 * 极简 JSON DOM。只为脚本格式服务：不引 kotlinx-serialization（体积预算未批），
 * 也不用 org.json（:core 必须零 Android 依赖）。
 */
internal sealed class JsonValue {
    object Null : JsonValue()
    class Bool(val value: Boolean) : JsonValue()
    class Num(val value: Double) : JsonValue()
    class Str(val value: String) : JsonValue()
    class Arr(val items: List<JsonValue>) : JsonValue()
    class Obj(val fields: Map<String, JsonValue>) : JsonValue()
}

internal object Json {

    private const val MAX_DEPTH = 32

    /** 解析失败统一抛 [ScriptFormatException]。 */
    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    private class Parser(private val src: String) {
        private var pos = 0
        private var depth = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != src.length) fail("JSON 结束后仍有多余内容")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= src.length) fail("意外的文件结尾")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> {
                    expectWord("true")
                    JsonValue.Bool(true)
                }
                'f' -> {
                    expectWord("false")
                    JsonValue.Bool(false)
                }
                'n' -> {
                    expectWord("null")
                    JsonValue.Null
                }
                else -> {
                    if (c == '-' || c in '0'..'9') parseNumber()
                    else fail("非法字符 '$c'")
                }
            }
        }

        private fun parseObject(): JsonValue.Obj {
            enter()
            pos++ // '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                leave()
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("对象的键必须是字符串")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') fail("键 \"$key\" 后缺少 ':'")
                pos++
                fields[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        leave()
                        return JsonValue.Obj(fields)
                    }
                    else -> fail("对象成员之间缺少 ','")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            enter()
            pos++ // '['
            val items = ArrayList<JsonValue>(4)
            skipWhitespace()
            if (peek() == ']') {
                pos++
                leave()
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        leave()
                        return JsonValue.Arr(items)
                    }
                    else -> fail("数组元素之间缺少 ','")
                }
            }
        }

        private fun parseString(): String {
            if (peek() != '"') fail("期望字符串")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) fail("字符串未闭合")
                when (val c = src[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) fail("转义序列未完成")
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) fail("\\u 转义缺少 4 位十六进制")
                                var code = 0
                                repeat(4) {
                                    val h = src[pos++]
                                    val d = when (h) {
                                        in '0'..'9' -> h - '0'
                                        in 'a'..'f' -> h - 'a' + 10
                                        in 'A'..'F' -> h - 'A' + 10
                                        else -> fail("非法十六进制字符 '$h'")
                                    }
                                    code = code * 16 + d
                                }
                                sb.append(code.toChar())
                            }
                            else -> fail("未知转义 '\\$e'")
                        }
                    }
                    else -> {
                        if (c < ' ') fail("字符串中出现未转义的控制字符")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            if (pos < src.length && src[pos] == '.') {
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val raw = src.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: fail("非法数字 '$raw'")
            if (value.isNaN() || value.isInfinite()) fail("数字越界 '$raw'")
            return JsonValue.Num(value)
        }

        private fun expectWord(word: String) {
            if (!src.startsWith(word, pos)) fail("期望 '$word'")
            pos += word.length
        }

        private fun peek(): Char {
            if (pos >= src.length) fail("意外的文件结尾")
            return src[pos]
        }

        private fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun enter() {
            if (++depth > MAX_DEPTH) fail("嵌套层级超过 $MAX_DEPTH")
        }

        private fun leave() {
            depth--
        }

        private fun fail(message: String): Nothing =
            throw ScriptFormatException("$message（位置 $pos）")
    }
}

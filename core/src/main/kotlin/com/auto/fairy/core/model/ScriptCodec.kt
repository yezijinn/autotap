package com.auto.fairy.core.model

/**
 * 脚本 JSON 编解码（纯 Kotlin 手写）。
 *
 * 解码策略：
 * - 未知字段忽略（向前兼容）；
 * - 坐标越界按 [0,1] 夹紧而不是报错（用户手编出错不应导致回放崩溃）；
 * - `d` 缺省为 0，`pd` 缺省为 -1（跟随脚本默认动作延迟），
 *   `rp` 缺省为 1；旧文件没有 `pd` 时按「跟随默认」解析，行为与既有版本一致；
 * - `"t":"delay"` 与新写法 `"t":"wait"` 都解析成 [Step.Wait]（纯等待步骤）；
 * - 文本开头的 UTF-8 BOM 会被剥掉（记事本另存的文件）；
 * - 版本号高于 [Script.VERSION] 直接拒绝（避免按旧语义错误回放）。
 */
internal object ScriptCodec {

    /** 单个时长上限（1 小时）：手编脚本写错量级时不至于把时间轴撑成天文数字。 */
    private const val MAX_DELAY_MS = 3_600_000L

    /**
     * 单步循环次数上限。比编辑器输入上限（999）宽：手编/旧文件里的更大值也能正常读入，
     * 只拦明显写错的量级（与 MAX_COMPILED_STROKES 一起兜住内存）。
     */
    private const val MAX_REPEAT = 9_999

    // ── 编码 ────────────────────────────────────────────────

    fun encode(script: Script): String {
        val sb = StringBuilder(256)
        sb.append("{\n")
        sb.append("  \"v\": ").append(script.v).append(",\n")
        sb.append("  \"id\": ")
        appendString(sb, script.id)
        sb.append(",\n")
        sb.append("  \"name\": ")
        appendString(sb, script.name)
        sb.append(",\n")
        sb.append("  \"lp\": ").append(script.loopTimes).append(",\n")
        sb.append("  \"ad\": ").append(script.defaultActionDelayMs).append(",\n")
        sb.append("  \"screen\": ")
        appendScreen(sb, script.screen)
        sb.append(",\n")
        sb.append("  \"steps\": [")
        if (script.steps.isEmpty()) {
            sb.append("]\n}")
            return sb.toString()
        }
        sb.append('\n')
        script.steps.forEachIndexed { i, step ->
            appendStep(sb, step, 1)
            if (i != script.steps.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n}")
        return sb.toString()
    }

    private fun appendScreen(sb: StringBuilder, screen: ScreenInfo) {
        sb.append("{\"w\":").append(screen.w)
            .append(",\"h\":").append(screen.h)
            .append(",\"dpi\":").append(screen.dpi)
            .append(",\"rot\":").append(screen.rot)
            .append('}')
    }

    private fun appendStep(sb: StringBuilder, step: Step, indent: Int) {
        val pad = "  ".repeat(indent)
        val innerPad = "  ".repeat(indent + 1)
        sb.append(pad)
        when (step) {
            is Step.Tap -> {
                sb.append("{ \"t\": \"tap\", \"d\": ")
                    .append(step.durationMs)
                appendTiming(sb, step.preDelayMs, step.repeat)
                sb.append(", \"p\": ")
                appendPath(sb, step.points)
                sb.append(" }")
            }
            is Step.Swipe -> {
                sb.append("{ \"t\": \"swipe\", \"d\": ")
                    .append(step.durationMs)
                appendTiming(sb, step.preDelayMs, step.repeat)
                sb.append(", \"p\": ")
                appendPath(sb, step.points)
                sb.append(" }")
            }
            is Step.Multi -> {
                sb.append("{ \"t\": \"multi\", \"d\": ")
                    .append(step.durationMs)
                appendTiming(sb, step.preDelayMs, step.repeat)
                sb.append(", \"p\": [")
                step.points.forEachIndexed { i, path ->
                    if (i > 0) sb.append(", ")
                    appendPath(sb, path)
                }
                sb.append("] }")
            }
            is Step.Wait -> {
                sb.append("{ \"t\": \"wait\", \"d\": ")
                    .append(step.durationMs)
                    .append(" }")
            }
            is Step.Loop -> {
                sb.append("{ \"t\": \"loop\", \"n\": ")
                    .append(step.times)
                    .append(", \"body\": [")
                if (step.body.isEmpty()) {
                    sb.append("] }")
                } else {
                    sb.append('\n')
                    step.body.forEachIndexed { i, child ->
                        appendStep(sb, child, indent + 1)
                        if (i != step.body.lastIndex) sb.append(',')
                        sb.append('\n')
                    }
                    sb.append(innerPad).append("] }")
                }
            }
        }
    }

    private fun appendTiming(sb: StringBuilder, preDelayMs: Long, repeat: Int) {
        sb.append(", \"pd\": ").append(preDelayMs)
            .append(", \"rp\": ").append(repeat)
    }

    private fun appendPath(sb: StringBuilder, points: List<Point>) {
        sb.append('[')
        points.forEachIndexed { i, p ->
            if (i > 0) sb.append(", ")
            sb.append('[').append(floatText(p.x)).append(", ").append(floatText(p.y)).append(']')
        }
        sb.append(']')
    }

    /** 0/1 写成整数，其余用 Float 的最短十进制表示。 */
    private fun floatText(value: Float): String {
        if (value == value.toLong().toFloat()) return value.toLong().toString()
        return value.toString()
    }

    private fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    // ── 解码 ────────────────────────────────────────────────

    fun decode(text: String): Script {
        // 记事本等编辑器写 UTF-8 时会带 BOM；不剥掉就整份脚本解析失败、静默不进列表
        val root = Json.parse(text.removePrefix("\uFEFF")) as? JsonValue.Obj
            ?: throw ScriptFormatException("脚本根节点必须是 JSON 对象")
        val v = root.int("v", Script.VERSION)
        if (v > Script.VERSION) {
            throw ScriptFormatException("脚本版本 $v 高于当前支持版本 ${Script.VERSION}")
        }
        val screen = root.obj("screen")?.let(::decodeScreen) ?: ScreenInfo.DEFAULT
        val stepsArr = root.arr("steps")
            ?: throw ScriptFormatException("脚本缺少 steps 数组")
        return Script(
            id = root.string("id", ""),
            name = root.string("name", ""),
            screen = screen,
            steps = stepsArr.map { decodeStep(it) },
            loopTimes = root.int("lp", Script.DEFAULT_LOOP_TIMES).coerceAtLeast(0),
            defaultActionDelayMs = root.long(
                "ad",
                Script.DEFAULT_ACTION_DELAY_MS,
            ).coerceIn(0L, MAX_DELAY_MS),
            v = v,
        )
    }

    private fun decodeScreen(obj: JsonValue.Obj): ScreenInfo {
        val w = obj.int("w", 0)
        val h = obj.int("h", 0)
        if (w <= 0 || h <= 0) throw ScriptFormatException("screen.w / screen.h 必须为正整数")
        return ScreenInfo(
            w = w,
            h = h,
            dpi = obj.int("dpi", 0),
            rot = obj.int("rot", ScreenInfo.ROT_UNKNOWN),
        )
    }

    private fun decodeStep(value: JsonValue): Step {
        val obj = value as? JsonValue.Obj
            ?: throw ScriptFormatException("steps 的元素必须是对象")
        val type = obj.string("t", "")
        val duration = obj.long("d", 0L).coerceIn(0L, MAX_DELAY_MS)
        // pd 缺省即「跟随脚本默认动作延迟」（-1）；
        // 显式写的数值按 0..上限夹取，-1 是唯一允许的负值。
        val preDelayRaw = obj.long("pd", Step.PRE_DELAY_DEFAULT)
        val preDelay = if (preDelayRaw == Step.PRE_DELAY_DEFAULT) {
            Step.PRE_DELAY_DEFAULT
        } else {
            preDelayRaw.coerceIn(0L, MAX_DELAY_MS)
        }
        // 旧文件没有 rp：语义就是「只执行一次」
        val repeat = obj.int("rp", 1).coerceIn(1, MAX_REPEAT)
        return when (type) {
            "tap" -> Step.Tap(decodePath(obj.arr("p")), duration, preDelay, repeat)
            "swipe" -> Step.Swipe(decodePath(obj.arr("p")), duration, preDelay, repeat)
            "multi" -> {
                val arr = obj.arr("p") ?: throw ScriptFormatException("multi 缺少 p")
                Step.Multi(
                    arr.map { path ->
                        val pathItems = (path as? JsonValue.Arr)?.items
                            ?: throw ScriptFormatException("multi 的每条手指轨迹必须是坐标数组")
                        decodePath(pathItems)
                    },
                    duration,
                    preDelay,
                    repeat,
                )
            }
            // "delay" 是旧写法，语义与 wait 完全一致（纯等待步骤）
            "wait", "delay" -> Step.Wait(duration)
            "loop" -> {
                // 与 d / rp 同策略夹取：否则 body 只有 Wait 时不产 stroke，
                // 编译上限永远触不到，主线程会按 n 的次数空转（ANR / OOM）。0 = 无限循环
                val times = obj.int("n", 0).coerceIn(0, MAX_REPEAT)
                if (times < 0) throw ScriptFormatException("loop.n 不能为负")
                val body = obj.arr("body")?.map { decodeStep(it) } ?: emptyList()
                Step.Loop(times, body)
            }
            "" -> throw ScriptFormatException("步骤缺少 t 字段")
            else -> throw ScriptFormatException("未知步骤类型 '$type'")
        }
    }

    private fun decodePath(items: List<JsonValue>?): List<Point> {
        if (items == null) throw ScriptFormatException("缺少坐标数组 p")
        return items.map { decodePoint(it) }
    }

    private fun decodePoint(value: JsonValue): Point {
        val pair = value as? JsonValue.Arr
            ?: throw ScriptFormatException("坐标必须是 [x, y] 数组")
        if (pair.items.size != 2) throw ScriptFormatException("坐标必须是 2 个数字")
        val x = (pair.items[0] as? JsonValue.Num)?.value
            ?: throw ScriptFormatException("坐标 x 必须是数字")
        val y = (pair.items[1] as? JsonValue.Num)?.value
            ?: throw ScriptFormatException("坐标 y 必须是数字")
        if (x.isNaN() || y.isNaN()) throw ScriptFormatException("坐标不能是 NaN")
        return Point(
            x = x.toFloat().coerceIn(0f, 1f),
            y = y.toFloat().coerceIn(0f, 1f),
        )
    }

    // ── DOM 取值辅助 ─────────────────────────────────────────

    private fun JsonValue.Obj.field(key: String): JsonValue? = fields[key]

    private fun JsonValue.Obj.arr(key: String): List<JsonValue>? =
        (field(key) as? JsonValue.Arr)?.items

    private fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = field(key) as? JsonValue.Obj

    private fun JsonValue.Obj.string(key: String, fallback: String): String =
        (field(key) as? JsonValue.Str)?.value ?: fallback

    private fun JsonValue.Obj.int(key: String, fallback: Int): Int =
        (field(key) as? JsonValue.Num)?.value?.toInt() ?: fallback

    private fun JsonValue.Obj.long(key: String, fallback: Long): Long =
        (field(key) as? JsonValue.Num)?.value?.toLong() ?: fallback
}

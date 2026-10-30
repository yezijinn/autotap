package com.auto.fairy.core.model

/**
 * 一个可回放脚本（文件即脚本：`files/scripts/<id>.json`）。
 *
 * 手写编解码，不依赖任何序列化库。字段形状严格对齐设计方案 3.7：
 * ```
 * { "v":1, "id":"s_01H", "name":"每日签到", "lp":0, "ad":1000,
 *   "screen": {"w":1080,"h":2400,"dpi":420,"rot":0},
 *   "steps":[ {"t":"tap","d":60,"pd":1000,"rp":1,"p":[[0.5,0.62]]}, ... ] }
 * ```
 *
 * 脚本级属性：
 * - [loopTimes]：整条脚本重复次数，0 = 无限（权威值，随脚本一起存）；
 * - [defaultActionDelayMs]：新建步骤时的默认动作延迟，0 = 无延迟；
 * - [screen]：录制/编辑时的真实分辨率，用于像素与归一化坐标互算。
 */
data class Script(
    val id: String,
    val name: String,
    val screen: ScreenInfo = ScreenInfo.DEFAULT,
    val steps: List<Step>,
    val loopTimes: Int = DEFAULT_LOOP_TIMES,
    val defaultActionDelayMs: Long = DEFAULT_ACTION_DELAY_MS,
    val v: Int = VERSION,
) {
    /** 序列化为 JSON 文本（缩进格式，落盘与导出都是这一份）。 */
    fun toJson(): String = ScriptCodec.encode(this)

    companion object {
        const val VERSION: Int = 1

        /** 0 = 无限循环：点击器默认一直跑到用户停止。 */
        const val DEFAULT_LOOP_TIMES: Int = 0

        /** 默认动作延迟：每个动作执行前的等待（毫秒）。 */
        const val DEFAULT_ACTION_DELAY_MS: Long = 1_000L

        fun fromJson(text: String): Script = ScriptCodec.decode(text)
    }
}

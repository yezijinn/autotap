package com.auto.fairy.editor

import com.auto.fairy.core.model.Step

/**
 * 「动作延迟」输入框的回显与解析。
 *
 * 存储值 [Step.PRE_DELAY_DEFAULT]（-1）表示跟随脚本默认动作延迟，输入框里留空。
 * 为什么不显示「默认」二字：这个输入框是 `inputType="number"`，装不下中文，
 * 用户一旦点进去改动，文本就变成「0默认」这类既解析不了、又看不出结果的东西。
 * 留空是数字键盘也能表达的状态，含义写在输入框下方的提示里（含当前默认值）。
 *
 * 回显与解析必须成对处理：只把数字写回去不回显「跟随」，就会把 -1 当普通数值
 * 夹成 0——用户只是打开一次步骤对话框再确认，动作延迟就被静默清成无延迟。
 */
internal object ActionDelayField {

    /** 输入框展示值：跟随默认时留空，否则给毫秒数。 */
    fun display(preDelayMs: Long): String =
        if (preDelayMs == Step.PRE_DELAY_DEFAULT) "" else preDelayMs.toString()

    /**
     * 解析输入框：留空 / 负数 / 非法文本 → 跟随默认；其他按 0..[maxMs] 夹取。
     *
     * 留空表示「没指定」，与 `ScriptCodec` 的「pd 缺省即跟随默认」同一口径；
     * 想要真的零延迟就填 0（提示里写明了两种写法的区别）。
     * 非法值按跟随默认处理，避免一次误输入把延迟锁死成某个数字。
     */
    fun parse(text: String?, maxMs: Long): Long {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return Step.PRE_DELAY_DEFAULT
        val value = raw.toLongOrNull() ?: return Step.PRE_DELAY_DEFAULT
        if (value < 0L) return Step.PRE_DELAY_DEFAULT
        return value.coerceAtMost(maxMs)
    }
}

package com.auto.fairy.editor

/**
 * 循环次数输入的统一口径（脚本级与步骤级共用一个上限）。
 *
 * 两处输入框各写一份 999 时，改上限只改一处就会出现「脚本级能填 2000、步骤级只能 999」，
 * 而两者在编译期根本是同一条循环路径。
 */
internal const val MAX_LOOP_TIMES: Int = 999

/** 解析循环次数输入：空 / 非数字按 0（= 无限）处理。 */
internal fun parseLoopTimes(text: String?): Int =
    text?.trim()?.toIntOrNull()?.coerceIn(0, MAX_LOOP_TIMES) ?: 0

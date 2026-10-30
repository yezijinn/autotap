package com.auto.fairy

import com.auto.fairy.core.model.Step

/**
 * 步数口径：等待也算一步，循环体里的步骤同样算。
 *
 * 编辑器里每个步骤（含等待）都是一张可编辑卡片，循环体展开后也各自成行，
 * 列表卡片与「第 N 步 / 共 M 步」必须用同一口径，否则用户会看到「2 步」
 * 的脚本进编辑器有 3 张卡片。
 */
object StepCount {

    /** 总步数 = 顶层步骤 + 循环体步骤（循环本身也算一步，因为它是独立卡片）。 */
    fun total(steps: List<Step>): Int {
        var count = 0
        fun walk(list: List<Step>) {
            for (step in list) {
                count++
                if (step is Step.Loop) walk(step.body)
            }
        }
        walk(steps)
        return count
    }
}

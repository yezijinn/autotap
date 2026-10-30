package com.auto.fairy.core.compiler

import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step

/**
 * Script → Timeline 编译器。
 *
 * ## 无限循环（times = 0）策略
 * 编译期把循环体展开 **恰好 1 次**，并记录
 * 「稳态重复区间」；运行期由 Scheduler 生成两套切片计划（首轮 / 稳态），引擎按
 * 周期平移时间基准循环游标。
 *
 * 为什么不按固定大次数（如 1000）展开：
 * - 展开 N 次只增加内存与 offset 计算，调度器本来就是按绝对时间流式切片的；
 * - 1 次迭代的重复单元让暂停/续播的基准平移与降级恢复游标最简单；
 * - 嵌套无限循环（`loop(0){ A; loop(0){ B } }`）语义上等价于 `A` 播一次、`B` 无限，
 *   采用 1 次展开时该等价关系直接成立，N 次展开反而要额外合并重复区间。
 *
 * 无限循环之后的步骤永远不可达，编译期丢弃（与运行期语义一致）。
 * 无限循环体时长必须 > 0，否则周期为 0 无法推进时间轴，直接报错。
 */
object ScriptCompiler {

    /** 展开后的 stroke 总量上限，防止手编脚本把内存打爆。 */
    const val MAX_COMPILED_STROKES: Int = 200_000

    fun compile(script: Script): Timeline {
        val builder = Builder(script.id, script.defaultActionDelayMs)
        builder.compileSteps(script.steps)
        return builder.build()
    }

    private class Builder(
        private val scriptId: String,
        /** 脚本默认动作延迟：步骤的 pd 为 -1（跟随默认）时取它。 */
        private val defaultActionDelayMs: Long,
    ) {
        private val strokes = ArrayList<CompiledStroke>()
        private var cursorMs = 0L

        /** 稳态重复区间的首条 stroke 下标；-1 表示没有无限循环。 */
        private var repeatStartIndex = -1
        private var repeatStartMs = -1L

        /** 已遇到无限循环，后续步骤不可达。 */
        private var terminated = false

        fun compileSteps(steps: List<Step>) {
            for (step in steps) {
                if (terminated) return
                when (step) {
                    is Step.Tap -> compileAction(
                        Step.effectivePreDelay(step.preDelayMs, defaultActionDelayMs),
                        step.repeat,
                    ) {
                        emit(listOf(step.points), step.durationMs)
                    }

                    is Step.Swipe -> compileAction(
                        Step.effectivePreDelay(step.preDelayMs, defaultActionDelayMs),
                        step.repeat,
                    ) {
                        emit(listOf(step.points), step.durationMs)
                    }

                    is Step.Multi -> compileAction(
                        Step.effectivePreDelay(step.preDelayMs, defaultActionDelayMs),
                        step.repeat,
                    ) {
                        emit(step.points, step.durationMs)
                    }

                    is Step.Wait -> advance(step.durationMs)
                    is Step.Loop -> compileLoop(step)
                }
            }
        }

        /**
         * 动作类步骤：每轮先推进「动作延迟」再产出 stroke。
         *
         * 动作延迟只是时间轴上的空白段，不产生任何 stroke，因此 R10（片内不重叠）天然成立；
         * 单步循环按次数完整展开，与脚本级循环在编译产物上再无区别。
         */
        private inline fun compileAction(
            preDelayMs: Long,
            repeatTimes: Int,
            emitOnce: () -> Unit,
        ) {
            val times = if (repeatTimes < 1) 1 else repeatTimes
            repeat(times) {
                if (preDelayMs > 0L) advance(preDelayMs)
                emitOnce()
            }
        }

        private fun emit(paths: List<List<Point>>, durationMs: Long) {
            if (strokes.size >= MAX_COMPILED_STROKES) {
                throw IllegalArgumentException("脚本展开后 stroke 数超过上限 $MAX_COMPILED_STROKES")
            }
            strokes.add(CompiledStroke(cursorMs, durationMs, paths))
            advance(durationMs)
        }

        private fun advance(deltaMs: Long) {
            val next = cursorMs + deltaMs
            if (next < cursorMs) {
                throw IllegalArgumentException("脚本时间轴溢出（累计时长超过 Long 上限）")
            }
            cursorMs = next
        }

        private fun compileLoop(loop: Step.Loop) {
            if (loop.times == 0) {
                if (loop.body.isEmpty()) {
                    throw IllegalArgumentException("无限循环的循环体为空，无法推进时间轴")
                }
                // 只展开 1 次；记录稳态区间。若体内还有无限循环，内层已经设置了
                // 更靠前的稳态区间，语义上内层才是真正的重复单元，不覆盖。
                val bodyStartMs = cursorMs
                val startIndex = strokes.size
                compileSteps(loop.body)
                if (!terminated) {
                    repeatStartIndex = startIndex
                    repeatStartMs = bodyStartMs
                    terminated = true
                }
                return
            }
            repeat(loop.times) {
                if (terminated) return
                compileSteps(loop.body)
            }
        }

        fun build(): Timeline {
            val durationMs = cursorMs
            val periodMs = if (repeatStartIndex >= 0) durationMs - repeatStartMs else 0L
            if (repeatStartIndex >= 0 && periodMs <= 0L) {
                throw IllegalArgumentException("无限循环一次迭代时长为 0，无法回放")
            }
            // 不重叠（R10）不需要在这里再断言：emit() 只把 cursorMs 当作 startMs 并立刻前进
            // 一个 durationMs，因此产物天然首尾相接；Scheduler 对任意 Timeline 还有一次全局校验。
            return Timeline(
                scriptId = scriptId,
                strokes = strokes.toList(),
                durationMs = durationMs,
                repeatStartIndex = repeatStartIndex,
                repeatPeriodMs = periodMs,
            )
        }
    }
}

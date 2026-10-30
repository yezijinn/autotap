package com.auto.fairy.editor

import android.content.Context
import com.auto.fairy.R
import com.auto.fairy.StepCount
import com.auto.fairy.core.model.Step

/** 步骤类型：编辑对话框标题共用的枚举，避免各处 when 分支各写一份映射。 */
enum class StepKind(
    val titleRes: Int,
) {
    TAP(R.string.step_tap),
    SWIPE(R.string.step_swipe),
    WAIT(R.string.step_wait),
    LOOP(R.string.step_loop),
    MULTI(R.string.step_multi),
    ;

    companion object {
        fun of(step: Step): StepKind = when (step) {
            is Step.Tap -> TAP
            is Step.Swipe -> SWIPE
            is Step.Wait -> WAIT
            is Step.Loop -> LOOP
            is Step.Multi -> MULTI
        }
    }
}

/** 步骤卡片摘要：主行给动作与像素坐标，副行给归一化值或补充信息 */
object StepSummary {

    /** 动作延迟展示：跟随默认时显示「默认」，否则显示毫秒数。 */
    private fun delayText(context: Context, preDelayMs: Long): String =
        if (preDelayMs == Step.PRE_DELAY_DEFAULT) {
            context.getString(R.string.step_delay_default)
        } else {
            preDelayMs.toString()
        }

    fun summaryOf(
        context: Context,
        step: Step,
        screen: Pair<Int, Int>,
    ): Pair<String, String> {
        val w = screen.first
        val h = screen.second
        return when (step) {
            is Step.Tap -> {
                val point = step.points.first()
                val (px, py) = CoordDisplay.toPx(point, w, h)
                context.getString(R.string.step_summary_tap) to
                    context.getString(
                        R.string.step_summary_tap_sub,
                        px,
                        py,
                        step.durationMs,
                        delayText(context, step.preDelayMs),
                        step.repeat,
                    )
            }

            is Step.Swipe -> {
                val from = step.points.first()
                val to = step.points.last()
                val (fx, fy) = CoordDisplay.toPx(from, w, h)
                val (tx, ty) = CoordDisplay.toPx(to, w, h)
                context.getString(R.string.step_summary_swipe) to
                    context.getString(
                        R.string.step_summary_swipe_sub,
                        fx,
                        fy,
                        tx,
                        ty,
                        step.durationMs,
                        delayText(context, step.preDelayMs),
                        step.repeat,
                    )
            }

            is Step.Wait -> context.getString(R.string.step_summary_delay, step.durationMs) to
                context.getString(R.string.step_summary_delay_sub)

            is Step.Loop -> {
                val times = if (step.times <= 0) {
                    context.getString(R.string.editor_loop_infinite)
                } else {
                    context.getString(R.string.step_loop_times_value, step.times)
                }
                context.getString(R.string.step_summary_loop, times, StepCount.total(step.body)) to
                    context.getString(R.string.step_summary_loop_sub)
            }

            is Step.Multi -> context.getString(R.string.step_summary_multi, step.points.size) to
                context.getString(R.string.step_summary_multi_sub, step.durationMs)
        }
    }
}


package com.auto.fairy.core.inject

import com.auto.fairy.core.model.MAX_BATCH_DURATION_MS
import com.auto.fairy.core.model.MAX_STROKES_PER_BATCH

/** 后端延迟等级，仅用于诊断页展示与调度策略选择。 */
enum class LatencyClass { LOW, NORMAL, HIGH }

/** 后端健康状态（诊断页展示用）。 */
enum class Health { ALIVE, DEAD }

/**
 * 注入后端能力协商。
 *
 * Scheduler 只按这些值切片：任何一批都不超过 [maxStrokesPerBatch] 条 stroke
 * 且不超过 [maxBatchDurationMs] 时长。
 *
 * [maxSliceMs] 是「切片跨度」上限，与 [maxBatchDurationMs]（系统对单次手势的硬上限）
 * 分开：它表达的是调度粒度，用来换取命令响应速度。一个切片就是引擎的一个命令消费点，
 * 片越长时间轴上的暂停/停止越迟生效（无障碍通道整条时间轴落进一片时实测 3–6s）。
 * 0 表示不限，保持旧行为。
 *
 * 相邻两次按压的间隔下限不在这里：那是「按下到按下」的通道约束，由后端在派发时
 * 自己兜住（见 A11yBackend.dispatchBatch），调度器不预留片间空白。
 */
data class Caps(
    val maxStrokesPerBatch: Int = MAX_STROKES_PER_BATCH,
    val maxBatchDurationMs: Long = MAX_BATCH_DURATION_MS,
    val maxSliceMs: Long = 0L,
    val latencyClass: LatencyClass = LatencyClass.NORMAL,
) {
    companion object {
        /**
         * 无障碍（dispatchGesture）通道的切片跨度。
         *
         * 片长 = 暂停/停止的生效延迟：整条时间轴落进一片时实测 3–6s 才停得下来。
         * 400ms 是「响应够快」和「派发次数不至于暴涨」的折中，实测暂停 <0.5s 生效。
         * 注意它切不开单条长手势：一条 2.2s 的滑动仍独占一片，片内的后续命令要等
         * 这条手势结束（片内串行派发）；暂停/停止则由后端的 requestAbort 提前收尾。
         */
        const val ACCESSIBILITY_SLICE_SPAN_MS = 400L

        /** 无障碍（dispatchGesture）通道的硬上限。 */
        @JvmField
        val ACCESSIBILITY: Caps = Caps(
            // 与 A11yBackend 的 caps 保持一致：未连接时诊断页也显示「高」，
            // 否则同一设备在「连上/没连上」之间会读出两个延迟等级。
            latencyClass = LatencyClass.HIGH,
            maxSliceMs = ACCESSIBILITY_SLICE_SPAN_MS,
        )
    }
}

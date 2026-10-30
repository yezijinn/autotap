package com.auto.fairy

import com.auto.fairy.core.compiler.CompiledStroke
import com.auto.fairy.core.model.Point

/** 一条待注入的笔画（多指手势里每根手指一条，共享同一 startMs）。 */
internal class PendingStroke(
    val points: List<Point>,
    val startMs: Long,
    val durationMs: Long,
)

/**
 * 一个可派发的批次；[firstStartMs] 是该批首个动作相对片首的偏移。
 *
 * 不变式：批次内所有笔画在时间上**互相重叠**（同一次按下），因此编进同一个
 * `GestureDescription` 时它们只是一次按压缩放出来的多根手指。
 */
internal class Batch(val strokes: List<PendingStroke>, val firstStartMs: Long)

/**
 * 把切片重排成「动作间隔 ≥ minGapMs」并分组为可派发的批次。
 *
 * ## 为什么批次内只允许时间重叠的笔画（时序审查 R11）
 * `GestureDescription` 的多条 stroke 是**同一次手势**的多根手指：pointerId 按 stroke
 * 下标分配，共享同一个 downTime。把「前一笔抬起、后一笔接着按下」的两条 stroke 放进
 * 同一个 description 时，接收方看到的是同一根手指一直按着（第一根指针始终不抬起），
 * 于是每一次点击都不成立。MuMu/API32 实测：40 次相邻点击（间隔 60ms、按住 60ms）
 * 只产生 **1 次** `touchstart`；而间隔 300ms 的两笔放进同一 description 仍是 2 次
 * 独立点击（有 240ms 真正抬手空档）。因此分批规则改为：
 *
 * - 时间上重叠（同一次按压的多指）→ 同一批次；
 * - 前后接续的笔画 → 各自独立派发，绝不合并。
 *
 * 重排只做整体后移，不改变任意两个动作的先后顺序。
 */
internal object GestureBatching {

    /**
     * 相邻两次独立按压的**起点到起点**下限：不小于通道下限，也不小于上一笔按压时长
     * （同一次按压不可能在上一笔还没抬起时开始）。
     *
     * 这是「不早于」约束，不是固定延迟：计划间隔本来就够大时调用方算出的补偿为 0；
     * 只有脚本把两笔排得比通道下限还密时才起作用。
     */
    fun startToStartFloorMs(previousDurationMs: Long, minGapMs: Long): Long =
        maxOf(minGapMs, previousDurationMs)

    fun build(strokes: List<CompiledStroke>, minGapMs: Long): List<Batch> {
        val reordered = ArrayList<PendingStroke>(strokes.size)
        var shiftMs = 0L
        var hasPrevious = false
        var lastStartMs = 0L
        var lastDurationMs = 0L

        for (stroke in strokes) {
            // 系统不接受 0 时长（StrokeDescription 要求 > 0），这里抬到 1ms。
            // 抬升只影响交给系统的那一笔，不进入时间轴：脚本 d=0 的相邻间隔仍是脚本写的值
            // （实测 pd=150/d=0：每拍按住 1ms，起点间隔 150ms 而不是 151ms）。
            val duration = stroke.durationMs.coerceAtLeast(1L)
            var start = stroke.startMs + shiftMs
            if (hasPrevious) {
                // 同一次按压不可能在上一笔还没抬起时开始：起始间隔取「通道下限」与
                // 「上一笔已经抬起」两者的较大值。否则重排会造出真正重叠的两笔，
                // 它们会被当成多指同发（同一手势），点击再次丢失。
                val earliest = lastStartMs + startToStartFloorMs(lastDurationMs, minGapMs)
                if (start < earliest) {
                    shiftMs += earliest - start
                    start = earliest
                }
            }
            for (path in stroke.paths) {
                reordered.add(PendingStroke(path, start, duration))
            }
            hasPrevious = true
            lastStartMs = start
            lastDurationMs = duration
        }

        // 批次只承载「时间上重叠」的笔画，也就是同一个多指步骤的各根手指：
        // 单批手指数与跨度上限因此由 Scheduler.validate（≤20 指、≤60s）一次性保证，
        // 这里不再重复设阈值。
        val batches = ArrayList<Batch>()
        var current = ArrayList<PendingStroke>()
        var groupStart = 0L
        var groupEnd = 0L
        for (pending in reordered) {
            val end = pending.startMs + pending.durationMs
            val joins = current.isNotEmpty() && pending.startMs < groupEnd
            if (!joins && current.isNotEmpty()) {
                batches.add(Batch(current, groupStart))
                current = ArrayList()
            }
            if (current.isEmpty()) {
                groupStart = pending.startMs
                groupEnd = end
            } else if (end > groupEnd) {
                groupEnd = end
            }
            current.add(pending)
        }
        if (current.isNotEmpty()) batches.add(Batch(current, groupStart))
        return batches
    }
}

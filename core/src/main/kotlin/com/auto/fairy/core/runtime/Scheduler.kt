package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.CompiledStroke
import com.auto.fairy.core.compiler.Timeline
import com.auto.fairy.core.inject.Caps

/**
 * 把 [Timeline] 切成后端可执行的时间片。
 *
 * 切片规则（全部在编译/调度期完成，运行期零分配）：
 * 1. 每片手指数 <= [Caps.maxStrokesPerBatch]（20）；
 * 2. 每片时长 <= [Caps.maxBatchDurationMs]（60s），从片首 stroke 起算到最后一条 stroke 抬起；
 * 3. [Caps.maxSliceMs] > 0 时再按片跨度切一次，把时间轴切成更细的命令消费点
 *    （暂停/停止的生效延迟由片长决定）；单条 stroke 超长时仍独占一片；
 * 4. stroke 之间严格不重叠（R10）。[validate] 对整条时间轴做一次全局校验，
 *    任何违反都视为内核 bug 直接抛错，绝不悄悄放行。
 *
 * 片与片之间不加任何空隙：相邻两片的最后一次按下与下一次按下由后端统一兜住
 * （见 A11yBackend 的起点间隔下限）。在调度层再加空隙会让「片内允许 0 间隔、
 * 片间强加间隔」自相矛盾，实测 40 次 60ms 点击被拉成 2.6s。
 *
 * 单条 stroke 自身超出 caps 时无法再切，直接抛错（例如 >60s 的长按），
 * 而不是静默截断——截断会改变用户手势语义。
 */
class Scheduler(private val caps: Caps = Caps()) {

    fun schedule(timeline: Timeline): Schedule {
        validate(timeline)
        val first = buildPlan(
            timeline = timeline,
            from = 0,
            baseMs = 0L,
            nominalDurationMs = timeline.durationMs,
        )
        val steady = if (timeline.isRepeating) {
            buildPlan(
                timeline = timeline,
                from = timeline.repeatStartIndex,
                baseMs = timeline.repeatStartMs,
                nominalDurationMs = timeline.repeatPeriodMs,
            )
        } else {
            first
        }
        return Schedule(first = first, steady = steady)
    }

    private fun validate(timeline: Timeline) {
        val strokes = timeline.strokes
        var previousEnd = 0L
        for (stroke in strokes) {
            if (stroke.fingerCount > caps.maxStrokesPerBatch) {
                throw IllegalArgumentException(
                    "单条 stroke 含 ${stroke.fingerCount} 指，超过后端上限 ${caps.maxStrokesPerBatch}",
                )
            }
            if (stroke.durationMs > caps.maxBatchDurationMs) {
                throw IllegalArgumentException(
                    "stroke 时长 ${stroke.durationMs}ms 超过单批上限 ${caps.maxBatchDurationMs}ms",
                )
            }
            if (stroke.startMs < previousEnd) {
                throw IllegalStateException(
                    "时间轴出现重叠笔画 @${stroke.startMs}ms（R10 被破坏）",
                )
            }
            previousEnd = stroke.endMs
        }
        // 上界含 size：循环体只有 Wait 时 repeatStartIndex 恰等于 strokes.size（合法，稳态只有时间）
        if (timeline.isRepeating && timeline.repeatStartIndex !in 0..strokes.size) {
            throw IllegalStateException("非法重复起点 ${timeline.repeatStartIndex}")
        }
    }

    private fun buildPlan(
        timeline: Timeline,
        from: Int,
        baseMs: Long,
        nominalDurationMs: Long,
    ): Schedule.Plan {
        val strokes = timeline.strokes
        val to = strokes.size
        val slices = ArrayList<Slice>()
        var index = 0
        var i = from

        while (i < to) {
            val firstNominalStart = strokes[i].startMs - baseMs

            val firstIndex = i
            var fingerCount = 0
            var spanEnd = 0L

            while (i < to) {
                val stroke = strokes[i]
                // 片内时间参数一律相对片首，后端直接用 absoluteStartNs + stroke.startMs。
                val relStart = stroke.startMs - baseMs - firstNominalStart
                val relEnd = relStart + stroke.durationMs
                if (i > firstIndex) {
                    if (fingerCount + stroke.fingerCount > caps.maxStrokesPerBatch) break
                    if (relEnd > caps.maxBatchDurationMs) break
                    // 调度粒度：片太长时间轴就没有命令消费点，暂停/停止要等整片跑完。
                    // 单条笔画自身超过上限时仍独占一片（不切断手势语义）。
                    if (caps.maxSliceMs > 0L && relEnd > caps.maxSliceMs) break
                }
                fingerCount += stroke.fingerCount
                if (relEnd > spanEnd) spanEnd = relEnd
                i++
            }

            // 片内不重叠由 schedule() 开头的 validate() 一次性保证（全局检查覆盖所有相邻对），
            // 这里不再重复校验同一个不变式。

            // 稳态计划的时间基准与首轮不同，必须重建成片内相对坐标。
            val batch = ArrayList<CompiledStroke>(i - firstIndex)
            for (k in firstIndex until i) {
                val stroke = strokes[k]
                batch.add(
                    CompiledStroke(
                        startMs = stroke.startMs - baseMs - firstNominalStart,
                        durationMs = stroke.durationMs,
                        paths = stroke.paths,
                    ),
                )
            }

            slices.add(
                Slice(
                    index = index++,
                    startMs = firstNominalStart,
                    endMs = firstNominalStart + spanEnd,
                    strokes = batch,
                    strokeCount = fingerCount,
                ),
            )
        }

        val durationMs = if (slices.isEmpty()) nominalDurationMs
        else maxOf(nominalDurationMs, slices[slices.size - 1].endMs)
        return Schedule.Plan(slices = slices, durationMs = durationMs)
    }
}

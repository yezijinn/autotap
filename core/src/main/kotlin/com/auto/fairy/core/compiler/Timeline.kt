package com.auto.fairy.core.compiler

/**
 * 脚本的不可变编译产物：一次时间轴迭代的扁平 stroke 列表 + 无限循环信息。
 *
 * 两种形态：
 * 1. 无无限循环：[strokes] 是整个脚本，[durationMs] 为总时长（含尾部 delay）。
 *    引擎的 loopTimes 作用于此列表。
 * 2. 含无限循环：[strokes] = 前缀 + 循环体的一次迭代；[repeatStartIndex] 指向
 *    循环体首条 stroke，[repeatPeriodMs] 为该循环体的周期。前缀只播一次，
 *    之后由引擎反复游标 [repeatStartIndex] 起的稳态切片（见 Scheduler）。
 *
 * 运行期游标只用 (迭代序号, 稳态切片下标)，不需要重新编译。
 */
class Timeline internal constructor(
    val scriptId: String,
    val strokes: List<CompiledStroke>,
    val durationMs: Long,
    val repeatStartIndex: Int,
    val repeatPeriodMs: Long,
) {
    /** 是否存在脚本级无限循环（存在时稳态只跑循环体，仍受引擎 loopTimes 收口）。 */
    val isRepeating: Boolean get() = repeatStartIndex >= 0

    /** 稳态迭代的起始时间偏移。 */
    val repeatStartMs: Long get() = if (isRepeating) durationMs - repeatPeriodMs else 0L

    val strokeCount: Int get() = strokes.size

    override fun toString(): String =
        "Timeline(id=$scriptId, strokes=$strokeCount, duration=${durationMs}ms, " +
            "repeatFrom=$repeatStartIndex, period=${repeatPeriodMs}ms)"
}

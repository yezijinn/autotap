package com.auto.fairy.core.runtime

/**
 * 一套回放计划：首轮切片 + 稳态切片。
 *
 * 有脚本级无限循环时，首轮包含「前缀 + 循环体一次」，稳态只含循环体；
 * 无无限循环时两者是同一套（引擎按 loopTimes 整段重复）。
 *
 * 计划全部在 [Scheduler.schedule] 阶段预计算，运行期只读。
 */
class Schedule internal constructor(
    val first: Plan,
    val steady: Plan,
) {
    class Plan internal constructor(
        val slices: List<Slice>,
        /** 一次迭代的时长（含尾部 delay；等于末片的绝对末尾，片间不另加间隔）。 */
        val durationMs: Long,
    )
}

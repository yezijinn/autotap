package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.CompiledStroke

/**
 * 调度切片：时间轴上连续的一段，引擎一次 [com.auto.fairy.core.inject.InputBackend.execute] 的执行单位。
 *
 * 字段分两类：
 * - 计划值（[startMs]/[endMs]，相对当前迭代基准的毫秒偏移）由 Scheduler 预计算后不可变；
 * - 运行值（[absoluteStartNs]/[absoluteEndNs]/[cycle]）每次派发前由引擎写入。
 *
 * [strokes] 中的 `startMs` 一律是**相对片首**的偏移（首条必为 0），
 * 因此后端的执行时刻 = `absoluteStartNs + stroke.startMs * 1e6`。
 *
 * Slice 及其 [strokes] 在调度期一次性分配，循环回放时反复复用，
 * 因此回放主循环不为切片分配对象。
 */
class Slice internal constructor(
    val index: Int,
    @JvmField val startMs: Long,
    @JvmField val endMs: Long,
    @JvmField val strokes: List<CompiledStroke>,
    /** 手指数（预算计量单位，可能大于 strokes.size，因为 multi 一个单元含多指）。 */
    @JvmField val strokeCount: Int,
) {
    /** 引擎写入的计划绝对开始时刻（纳秒，System.nanoTime 基准）。 */
    @JvmField @Volatile var absoluteStartNs: Long = 0L

    /** 计划绝对结束时刻（纳秒）。 */
    @JvmField @Volatile var absoluteEndNs: Long = 0L

    /**
     * 所属迭代序号（从 0 开始），引擎在派发前写入。
     *
     * 生产代码不读：唯一读者是测试后端 FakeBackend。跨巡顺序断言
     * （BoundaryScenarioTest / LoopAndControlTest / PlaybackEngineTest 的
     * `records.map { it.cycle }`）全靠这一行；删了它，「每巡单块、无重号、
     * 跨巡不串」就只能靠时序间接推断。
     */
    @JvmField @Volatile var cycle: Long = 0L

    val durationMs: Long get() = endMs - startMs

    override fun toString(): String =
        "Slice(#$index, ${startMs}..${endMs}ms, strokes=${strokes.size}, fingers=$strokeCount)"
}

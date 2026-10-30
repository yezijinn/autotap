package com.auto.fairy.core.compiler

import com.auto.fairy.core.model.Point

/**
 * 编译后的最小调度单元（= 无障碍语义里的一条 stroke）。
 *
 * - 所有坐标仍是归一化 [0,1]，像素换算交给注入后端；
 * - 一个 [CompiledStroke] 内的多条 path 表示多指同时按下（Multi 步骤），
 *   这是唯一允许时间重叠的形态；不同 [CompiledStroke] 之间严格不重叠（R10）。
 *
 * 运行期只读，不产生任何对象分配。
 */
class CompiledStroke(
    @JvmField val startMs: Long,
    @JvmField val durationMs: Long,
    @JvmField val paths: List<List<Point>>,
) {
    val endMs: Long get() = startMs + durationMs

    /** 手指数量；也是批次数预算的计量单位。 */
    val fingerCount: Int get() = paths.size

    override fun toString(): String =
        "CompiledStroke(start=$startMs, dur=$durationMs, fingers=$fingerCount)"
}

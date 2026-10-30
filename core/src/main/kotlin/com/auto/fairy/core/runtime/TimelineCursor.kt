package com.auto.fairy.core.runtime

/**
 * 回放进度游标：迭代序号 + 稳态切片下标。
 *
 * 精度是「切片级」而非「笔画级」：从该切片开头重放，语义为 at-least-once
 * （宁可重放一片，也不跳过用户操作）。
 */
data class TimelineCursor(
    val cycle: Long,
    val sliceIndex: Int,
) {
    init {
        require(cycle >= 0L) { "cycle 不能为负" }
        require(sliceIndex >= 0) { "sliceIndex 不能为负" }
    }
}

package com.auto.fairy.core.model

/** 无障碍批次硬上限：单次 gesture 最多 20 条 stroke。 */
const val MAX_STROKES_PER_BATCH: Int = 20

/** 无障碍批次硬上限：单次 gesture 总时长 60 000ms。 */
const val MAX_BATCH_DURATION_MS: Long = 60_000L

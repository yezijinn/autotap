package com.auto.fairy.core.model

/**
 * 单批 stroke 上限：10。
 *
 * 注入通道实测（API 32）只交付到 11 指：11 指完整有 UP，12/13 只会落 11 指、收不到 UP，
 * 而 `dispatchGesture` 仍返回 true、回调也报成功——静默少指且按压不释放。
 * 取 10 留一指余量，越界时由 [com.auto.fairy.core.runtime.Scheduler.validate] 直接抛错，
 * 用户看到的是「脚本不合法」而不是点了没反应。
 */
const val MAX_STROKES_PER_BATCH: Int = 10

/** 无障碍批次硬上限：单次 gesture 总时长 60 000ms。 */
const val MAX_BATCH_DURATION_MS: Long = 60_000L

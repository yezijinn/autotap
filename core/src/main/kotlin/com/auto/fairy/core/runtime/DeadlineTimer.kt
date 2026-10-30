package com.auto.fairy.core.runtime

import java.util.concurrent.locks.LockSupport

/**
 * 绝对时限等待：粗粒度 park + 末段自旋。
 *
 * ## 为什么不能只 parkNanos
 * `LockSupport.parkNanos` 在 Windows 上落到 `WaitForSingleObject`，受系统定时器
 * 分辨率限制（默认 15.625ms）：请求 5ms 实测 p50 迟到 11ms、p95 迟到 11.1ms，
 * 直接叠加到每个笔画上，M1 的 p95<3ms 不可能达标。Linux/Android 的 futex
 * 精度通常在 0.1ms 量级，同一份代码不需要自旋 15ms。
 *
 * 对策：**先 park 到「截止时刻 - [spinPadNs]」，最后 [spinPadNs] 纯自旋**，
 * 即 park 只负责省电，精度由自旋兜底。自旋量按平台给定：
 * - Windows：20ms（覆盖 15.625ms 定时器刻度并留余量）；
 * - 其他（Linux/Android）：1ms。
 * 可用 `-Dautotap.spinPadMs=N` 覆盖，便于真机诊断页复测。
 *
 * 自旋不用 `Thread.onSpinWait()`：minSdk 28 上该 API 不存在（API 33 才加入），
 * 这里直接读 nanoTime，避免低版本设备 NoSuchMethodError。
 */
object DeadlineTimer {

    @JvmField
    val spinPadNs: Long = resolveSpinPadNs()

    private fun resolveSpinPadNs(): Long {
        val override = System.getProperty("autotap.spinPadMs")?.toLongOrNull()
        if (override != null && override in 0L..1_000L) return override * 1_000_000L
        val os = System.getProperty("os.name").orEmpty()
        return if (os.contains("win", ignoreCase = true)) 20_000_000L else 1_000_000L
    }

    /**
     * 阻塞到 [deadlineNs]（System.nanoTime 基准），返回时通常刚过截止时刻数微秒。
     *
     * 生产路径不调用本函数：`PlaybackEngine.awaitDeadline` 内联了同一算法（它要顺带
     * 检查 wakeRequested）。唯一调用方是单测后端 FakeBackend——测试打点必须与生产
     * 同精度，否则测到的是 Windows 定时器刻度，不是引擎。
     */
    fun awaitUntil(deadlineNs: Long) {
        while (true) {
            val remaining = deadlineNs - System.nanoTime()
            if (remaining <= 0L) return
            if (remaining > spinPadNs) {
                LockSupport.parkNanos(remaining - spinPadNs)
            }
            // remaining <= spinPadNs：自旋。
        }
    }
}

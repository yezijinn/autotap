package com.auto.fairy

/**
 * 录制时相邻两步手势之间的等待时长。
 *
 * 间隔必须从「上一次抬起」量到「这一次按下」。早期实现量到的是这一次的抬起，
 * 于是把当前手势自身的按压时长也算进了间隔：录两个间隔 300ms 的点击，回放是
 * 300ms + 这一次的按压时长；录「点击 → 滑动 800ms」，滑动前会多等 800ms。
 *
 * 抽成纯对象是为了能在 JVM 单测里固定住这条口径（RecordOverlay 本身依赖 WindowManager）。
 */
internal object RecordGap {

    /** 低于此值按此值记：再短的间隔在无障碍通道上也点不准。 */
    const val MIN_MS = 50L

    /**
     * 高于此值按此值记：长停顿只保留上限，避免一条脚本被录成几分钟的空等。
     *
     * 取 30 秒而不是更短：录制的空档是用户的真实节奏（等页面加载、等弹窗消失
     * 常有 5–15 秒）。早期取 3 秒，用户等 10 秒的动作会被静默压成 3 秒，
     * 回放时正是「延迟不按参数来」。
     */
    const val MAX_MS = 30_000L

    /**
     * @param previousUpAt 上一步手势抬起的事件时刻；0 表示这是第一步，不该补等待。
     * @param thisDownAt 本步手势按下的事件时刻。
     * @return 需要补的等待时长；null 表示前面没有步骤，不插 Wait。
     */
    fun between(previousUpAt: Long, thisDownAt: Long): Long? {
        if (previousUpAt <= 0L) return null
        return (thisDownAt - previousUpAt).coerceIn(MIN_MS, MAX_MS)
    }
}

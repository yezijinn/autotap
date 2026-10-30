package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.Timeline

/**
 * 回放控制命令。
 *
 * 铁律（设计方案 3.2）：悬浮窗 / 通知 / Activity 三方只能投递命令，禁止直接驱动
 * 引擎；引擎单实例 + 单 runToken。Start 携带脚本，其余命令无载荷。
 */
sealed class Command {

    data class Start(
        val timeline: Timeline,
        val loopTimes: Int = 1,
        val from: TimelineCursor? = null,
    ) : Command()

    object Pause : Command()

    object Resume : Command()

    object Stop : Command()
}

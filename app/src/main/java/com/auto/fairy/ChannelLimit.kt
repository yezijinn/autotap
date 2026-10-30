package com.auto.fairy

import com.auto.fairy.core.compiler.CompiledStroke

/**
 * 无障碍通道的「相邻按压起点间隔」下限判定。
 *
 * 这是纯计算：只回答「这条时间轴会不会被通道拉长」，不碰任何 Android API，
 * 因此可以单测（见 `ChannelLimitTest`）。真正做后移的是
 * [com.auto.fairy.GestureBatching]（片内）与 `A11yBackend.dispatchBatch`（片间）。
 */
internal object ChannelLimit {

    /**
     * 时间轴里是否存在「相邻两次按压的起点间隔小于 [minGapMs]」。
     *
     * 编译后的 stroke 按时间排序；多指同发是**一条 stroke 多条 path**（见
     * [com.auto.fairy.core.compiler.CompiledStroke]），不是多条同起点 stroke，
     * 因此这里不需要为多指留任何例外：两条 stroke 起点相同只可能来自
     * 「上一步 d=0 且本步 pd=0」，而那正是会被后端后移的情形，必须提示。
     * 真实间隔只有小于下限时后端才会把后续动作整体后移——此时整段比脚本更长，
     * 界面上必须有一句解释（[R.string.toast_gap_floor]），否则像是软件不听话。
     */
    fun tighterThanMinGap(strokes: List<CompiledStroke>, minGapMs: Long): Boolean {
        var previousStart = Long.MIN_VALUE
        for (stroke in strokes) {
            if (previousStart != Long.MIN_VALUE && stroke.startMs - previousStart < minGapMs) {
                return true
            }
            previousStart = stroke.startMs
        }
        return false
    }
}

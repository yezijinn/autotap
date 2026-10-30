package com.auto.fairy.overlay

/**
 * 圆点触摸窗的开关策略（纯逻辑，便于单测）。
 *
 * 1. 「坐标可拖」关掉时不建窗；
 * 2. 播放中一律不建窗：注入的点击与手指共用同一套命中测试，圆点窗若压在
 *    脚本要点的位置上，会先于目标应用收到 DOWN 并吞掉它（实测同一坐标
 *    有窗时目标按钮不响应、撤窗后响应）；
 * 3. 脚本点超过上限时只建前 N 个，且要给用户一次可见提示，不能静默少一半。
 */
object TouchWindowPolicy {

    /** 触摸窗上限：脚本再长也不会催出几百个窗口。 */
    const val MAX_MARKERS = 60

    fun shouldBuild(moveEnabled: Boolean, playing: Boolean): Boolean = moveEnabled && !playing

    fun count(markers: Int, limit: Int = MAX_MARKERS): Int =
        markers.coerceIn(0, limit.coerceAtLeast(0))

    fun overflow(markers: Int, limit: Int = MAX_MARKERS): Boolean = markers > limit

    /**
     * 圆点触摸窗（中心 [ax],[ay]，边长 [span]）是否压到矩形 [bLeft]..[bBottom]。
     *
     * 必须比「圆心在矩形内」更严格：窗本身是 span×span，真机实测圆心在迷你条
     * 下方 26px 时，窗口仍盖住播放键的下半部分——按播放完全没反应。
     */
    fun touches(
        ax: Int,
        ay: Int,
        span: Int,
        bLeft: Int,
        bTop: Int,
        bRight: Int,
        bBottom: Int,
    ): Boolean {
        val half = span / 2
        return ax - half < bRight && bLeft < ax - half + span &&
            ay - half < bBottom && bTop < ay - half + span
    }

    /**
     * 同一像素上的多个圆点归成一组：每组只建一个触摸窗。
     * 按组首次出现顺序排列，组内下标升序。
     *
     * 为什么必须归组：同一像素叠着两个窗时，只有后加的那个（下标更大的）能收到
     * 触摸，前一个永远拖不动——脚本里「原地重复点」的点位会有一半改不了坐标。
     */
    fun stacks(px: IntArray, py: IntArray): List<List<Int>> {
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        val n = minOf(px.size, py.size)
        for (i in 0 until n) {
            val key = (px[i].toLong() shl 32) xor (py[i].toLong() and 0xFFFFFFFFL)
            groups.getOrPut(key) { ArrayList() }.add(i)
        }
        return groups.values.toList()
    }

    /**
     * 重叠堆里「下一个被拖的」：从最上层（下标最大）开始逐次向下循环。
     *
     * 当前不在堆里（首次点击 / 刚重建窗口）→ 取最上层；否则取前一个，到底回绕。
     * 这样一颗一颗点就能把所有叠在一起的圆点都改到，不必先挪开上面那颗。
     */
    fun nextPick(stack: List<Int>, current: Int): Int {
        if (stack.isEmpty()) return current
        val at = stack.indexOf(current)
        if (at < 0) return stack.last()
        return stack[if (at == 0) stack.lastIndex else at - 1]
    }
}

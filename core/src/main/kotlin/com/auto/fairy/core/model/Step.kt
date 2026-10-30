package com.auto.fairy.core.model

/**
 * 语义步骤。坐标全部归一化到 [0,1]。
 *
 * 同一时刻只允许一个步骤占用时间轴：每个步骤从当前游标开始，持续 [durationMs]，
 * 因此编译产物天然满足「同一批次内 stroke 时序不重叠」（设计方案 R10）。
 *
 * 动作类步骤（[Tap] / [Swipe] / [Multi]）自带两项属性：
 * - [preDelayMs]：动作执行前的等待，不产生 stroke；
 * - [repeat]：本步骤自身重复次数，每次都带上前面的动作延迟。
 */
sealed class Step {

    companion object {
        /**
         * 「跟随脚本默认动作延迟」的哨兵值。
         *
         * 存 -1 而不是存具体毫秒数：这样用户改脚本的
         * defaultActionDelayMs 时，所有没被显式改过的步骤会一起跟着变；
         * 一旦用户手填过数值，就固化下来不再跟随。
         */
        const val PRE_DELAY_DEFAULT = -1L

        /** 实际生效的动作延迟（PRE_DELAY_DEFAULT 时取脚本默认值）。 */
        fun effectivePreDelay(preDelayMs: Long, scriptDefault: Long): Long =
            if (preDelayMs == PRE_DELAY_DEFAULT) scriptDefault.coerceAtLeast(0L)
            else preDelayMs
    }

    /** 动作本身的时长（按下到松开）；Loop 为其一次迭代的时长。 */
    abstract val durationMs: Long

    /** 动作执行前的等待；[Wait] / [Loop] 恒为 0。 */
    open val preDelayMs: Long get() = 0L

    /** 本步骤自身重复次数（>=1）；[Wait] / [Loop] 恒为 1。 */
    open val repeat: Int get() = 1

    /**
     * 在时间轴上的实际占用：动作延迟 + 动作时长，按 [repeat] 累计；
     * [Loop] 为其一次迭代的占用（与 [durationMs] 的口径一致）。
     *
     * 唯一口径：[PRE_DELAY_DEFAULT] 是「跟随脚本默认」的哨兵，必须由调用方给出脚本默认值。
     * 不提供「不带默认值」的重载——那样的重载只能把哨兵按 0 计，
     * 既不是精确值（少算 `脚本默认 * repeat`），早期实现更是把它当毫秒数累加，
     * 直接算出**负的**动作延迟。
     */
    fun totalMsWith(scriptDefault: Long): Long = when (this) {
        is Loop -> {
            var sum = 0L
            for (step in body) sum += step.totalMsWith(scriptDefault)
            if (times <= 1) sum else sum * times
        }

        else -> effectivePreDelay(preDelayMs, scriptDefault) * repeat + durationMs * repeat
    }

    /**
     * 单指点击/短按。[points] 为一条手指轨迹（通常 1 个点）。
     */
    data class Tap(
        val points: List<Point>,
        override val durationMs: Long,
        override val preDelayMs: Long = PRE_DELAY_DEFAULT,
        override val repeat: Int = 1,
    ) : Step() {
        init {
            require(points.isNotEmpty()) { "tap 至少需要 1 个点" }
            require(durationMs >= 0L) { "tap 时长不能为负: $durationMs" }
            require(preDelayMs >= 0L || preDelayMs == PRE_DELAY_DEFAULT) {
                "tap 动作延迟必须 >= 0 或为跟随默认: $preDelayMs"
            }
            require(repeat >= 1) { "tap 循环次数至少为 1: $repeat" }
        }
    }

    /** 单指滑动。[points] 为一条手指轨迹，首点按下、末点抬起。 */
    data class Swipe(
        val points: List<Point>,
        override val durationMs: Long,
        override val preDelayMs: Long = PRE_DELAY_DEFAULT,
        override val repeat: Int = 1,
    ) : Step() {
        init {
            require(points.isNotEmpty()) { "swipe 至少需要 1 个点" }
            require(durationMs >= 0L) { "swipe 时长不能为负: $durationMs" }
            require(preDelayMs >= 0L || preDelayMs == PRE_DELAY_DEFAULT) {
                "swipe 动作延迟必须 >= 0 或为跟随默认: $preDelayMs"
            }
            require(repeat >= 1) { "swipe 循环次数至少为 1: $repeat" }
        }
    }

    /**
     * 多指手势：[points] 的每个元素是一条手指轨迹，所有手指同时按下、同时抬起。
     * 这是设计里唯一允许「内部时间重叠」的形态（多指语义本身要求重叠）。
     */
    data class Multi(
        val points: List<List<Point>>,
        override val durationMs: Long,
        override val preDelayMs: Long = PRE_DELAY_DEFAULT,
        override val repeat: Int = 1,
    ) : Step() {
        init {
            require(points.isNotEmpty()) { "multi 至少需要 1 条手指轨迹" }
            require(points.all { it.isNotEmpty() }) { "multi 每条手指轨迹至少需要 1 个点" }
            require(durationMs >= 0L) { "multi 时长不能为负: $durationMs" }
            require(preDelayMs >= 0L || preDelayMs == PRE_DELAY_DEFAULT) {
                "multi 动作延迟必须 >= 0 或为跟随默认: $preDelayMs"
            }
            require(repeat >= 1) { "multi 循环次数至少为 1: $repeat" }
        }
    }

    /**
     * 独立可见的等待步骤，不产生 stroke。
     *
     * 它自己不再带动作延迟：它本身就是一次可见的等待，与动作内置的 [preDelayMs] 互不替代。
     * 旧格式的 `"t":"delay"` 解析后即本类型（见 [ScriptCodec]）。
     */
    data class Wait(override val durationMs: Long) : Step() {
        init {
            require(durationMs >= 0L) { "wait 时长不能为负: $durationMs" }
        }
    }

    /**
     * 循环。[times] = 0 表示无限循环（编译策略见 [com.auto.fairy.core.compiler.ScriptCompiler]）。
     */
    data class Loop(
        val times: Int,
        val body: List<Step>,
    ) : Step() {
        init {
            require(times >= 0) { "loop 次数不能为负: $times" }
        }

        /**
         * 一次迭代的占用。`Loop` 不知道自己所属脚本的默认动作延迟，这里的值把
         * 「跟随默认」按 0 计，因此是**下界**；要精确值用 [totalMsWith]。
         */
        override val durationMs: Long
            get() = totalMsWith(0L)
    }
}

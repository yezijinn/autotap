package com.auto.fairy.overlay

import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step

/**
 * 标记路径：定位「脚本里哪一个点的第几个坐标」。
 *
 * [Step] 是不可变 data class，拖动后只能整棵树重建；路径在摊平时一并记下，
 * 松手时交给 [replaceAt] 才能精确替换到原位置，不靠坐标值反查。
 */
sealed class MarkerPath {

    /** 第 [index] 步（相对当前列表）的 Tap 第 0 点。 */
    data class Tap(val index: Int) : MarkerPath()

    /** 第 [index] 步 Swipe 的第 [point] 个点（0=起点，last=终点）。 */
    data class Swipe(val index: Int, val point: Int) : MarkerPath()

    /** 第 [index] 步 Multi 的第 [finger] 指第 0 点。 */
    data class Multi(val index: Int, val finger: Int) : MarkerPath()

    /** 第 [index] 步 Loop，循环体内再按 [inner] 定位。 */
    data class Loop(val index: Int, val inner: MarkerPath) : MarkerPath()
}

/** 摊平后的一个标记点：归一化坐标 + 序号文案 + 路径。 */
data class FlatMark(
    val point: Point,
    val label: String,
    val path: MarkerPath,
    /** 滑动终点用空心点区分（与起点同号）。 */
    val hollow: Boolean = false,
    /**
     * 步骤展开序号（1..N，深度优先，循环体紧随其父），与 [PlaybackController] 的
     * 步骤时间轴编号同口径，仅用于「播放到第几步」高亮。
     */
    val ordinal: Int = 0,
)

/**
 * 把步骤树摊平成标记点。
 *
 * 序号口径与编辑器步骤卡片完全一致：顶层 1..N，循环体内「父序号.子序号」。
 * - Tap：画 1 点；
 * - Swipe：起点 + 终点都画，同号，终点空心；
 * - Multi：每根手指都画（M5 只画第一指，这里修正）；
 * - Wait：不画点但占序号（卡片序号必须连续）；
 * - Loop：不画点，体内递归并继承前缀；无限循环（times = 0）之后的步骤
 *   编译期就不可达（见 [com.auto.fairy.core.compiler.ScriptCompiler]），一并省略。
 */
object MarkerFlatten {

    fun of(steps: List<Step>): List<FlatMark> =
        walk(steps, "", intArrayOf(0), BooleanArray(1))

    private fun walk(
        steps: List<Step>,
        prefix: String,
        seq: IntArray,
        stopped: BooleanArray,
    ): List<FlatMark> {
        val out = ArrayList<FlatMark>()
        steps.forEachIndexed { index, step ->
            if (stopped[0]) return out
            val label = if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}"
            val ordinal = ++seq[0]
            when (step) {
                is Step.Tap -> step.points.firstOrNull()?.let {
                    out.add(FlatMark(it, label, MarkerPath.Tap(index), ordinal = ordinal))
                }

                is Step.Swipe -> {
                    step.points.firstOrNull()?.let {
                        out.add(
                            FlatMark(
                                it, label, MarkerPath.Swipe(index, 0),
                                ordinal = ordinal,
                            ),
                        )
                    }
                    if (step.points.size > 1) {
                        out.add(
                            FlatMark(
                                step.points.last(),
                                label,
                                MarkerPath.Swipe(index, step.points.lastIndex),
                                hollow = true,
                                ordinal = ordinal,
                            ),
                        )
                    }
                }

                is Step.Multi -> step.points.forEachIndexed { finger, fingerPoints ->
                    fingerPoints.firstOrNull()?.let {
                        out.add(
                            FlatMark(
                                it, label, MarkerPath.Multi(index, finger),
                                ordinal = ordinal,
                            ),
                        )
                    }
                }

                is Step.Loop -> {
                    // 体内先按「播一次」摊平（无限循环也只跑一轮），
                    // 之后的整棵树都不可达：与编译器逐字同一语义
                    walk(step.body, label, seq, stopped).forEach { inner ->
                        out.add(inner.copy(path = MarkerPath.Loop(index, inner.path)))
                    }
                    if (step.times == 0) stopped[0] = true
                }

                is Step.Wait -> Unit
            }
        }
        return out
    }
}

/** 用 [path] 指向的新坐标替换目标点，返回新脚本（纯函数，不改原对象）。 */
fun Script.replaceAt(path: MarkerPath, np: Point): Script = copy(steps = steps.replaceMarker(path, np))

private fun List<Step>.replaceMarker(path: MarkerPath, np: Point): List<Step> {
    if (path is MarkerPath.Loop) {
        return mapIndexed { i, step ->
            if (i == path.index && step is Step.Loop) {
                step.copy(body = step.body.replaceMarker(path.inner, np))
            } else {
                step
            }
        }
    }
    return mapIndexed { i, step ->
        when {
            path is MarkerPath.Tap && i == path.index && step is Step.Tap ->
                step.copy(points = listOf(np))

            path is MarkerPath.Swipe && i == path.index && step is Step.Swipe ->
                step.copy(
                    points = step.points.mapIndexed { k, p -> if (k == path.point) np else p },
                )

            path is MarkerPath.Multi && i == path.index && step is Step.Multi ->
                step.copy(
                    points = step.points.mapIndexed { f, fingerPoints ->
                        if (f == path.finger) {
                            fingerPoints.mapIndexed { k, p -> if (k == 0) np else p }
                        } else {
                            fingerPoints
                        }
                    },
                )

            else -> step
        }
    }
}

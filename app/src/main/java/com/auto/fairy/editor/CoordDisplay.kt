package com.auto.fairy.editor

import android.content.Context
import com.auto.fairy.ScreenMetrics
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import kotlin.math.roundToInt

/**
 * 坐标显示换算。
 *
 * 存储永远是归一化 [Point]；编辑界面需要像素值时统一走这里。
 *
 * 像素基准只用**当前设备**分辨率：注入就是 `归一化 × 当前宽高`
 * （见 A11yBackend 的 path 构造），按脚本里记录的旧分辨率反算会读出
 * 一个永远不会被点到的像素值。
 */
object CoordDisplay {

    /** 设备真实分辨率（含状态栏/导航栏，与注入坐标系一致）。 */
    fun device(context: Context): Pair<Int, Int> {
        val size = ScreenMetrics.size(context)
        return size.x to size.y
    }

    /** 编辑器与步骤卡片显示用的屏幕尺寸 = 当前设备。 */
    fun screenFor(context: Context): Pair<Int, Int> = device(context)

    /**
     * 脚本记录的分辨率：与当前设备不同才返回，供界面标注「录制于 …」。
     * 只用于提示，不参与任何换算。
     */
    fun recordedFor(context: Context, script: Script?): Pair<Int, Int>? {
        val recorded = script?.screen ?: return null
        val device = device(context)
        if (recorded.w == device.first && recorded.h == device.second) return null
        return recorded.w to recorded.h
    }

    fun toPx(point: Point, screenW: Int, screenH: Int): Pair<Int, Int> =
        (point.x * screenW).roundToInt() to (point.y * screenH).roundToInt()

    fun fromPx(px: Float, py: Float, screenW: Int, screenH: Int): Point = Point(
        (px / screenW.coerceAtLeast(1)).coerceIn(0f, 1f),
        (py / screenH.coerceAtLeast(1)).coerceIn(0f, 1f),
    )
}

package com.auto.fairy.overlay

import android.content.Context
import android.graphics.Color

/**
 * 步骤序号的字体参数：颜色、字号、间距。
 *
 * 与 [OrdinalStylePreview]、[CoordinateMarkers] 共用；改动经
 * [OrdinalStyleStore] 持久化，设置页与坐标层都从同一处读。
 */
data class OrdinalStyle(
    val color: Int,
    val sizeSp: Float,
    /** 数字与十字标记的左右间距（dp）。 */
    val gapDp: Float = DEFAULT_GAP_DP,
) {
    companion object {

        const val DEFAULT_GAP_DP = 6f
        const val MAX_GAP_DP = 20f
        /** 默认值：紫字 + 红描边（与既有视觉一致）。 */
        val DEFAULT = OrdinalStyle(
            color = Color.parseColor("#8B5CF6"),
            sizeSp = 15f,
            gapDp = DEFAULT_GAP_DP,
        )

        /**
         * 七彩 + 黑白九色标准色板：设置页直接点选，
         * 顺序与布局里的色块一一对应。
         */
        val PALETTE: List<Int> = listOf(
            Color.parseColor("#EB5757"),
            Color.parseColor("#F2994A"),
            Color.parseColor("#F2C94C"),
            Color.parseColor("#27AE60"),
            Color.parseColor("#2BB6C4"),
            Color.parseColor("#2F6FED"),
            Color.parseColor("#8B5CF6"),
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#1A1D21"),
        )

        const val MIN_SIZE_SP = 8f
        const val MAX_SIZE_SP = 24f
    }
}

/**
 * 字体参数的持久化（SharedPreferences，键前缀 ordinal_）。
 *
 * 存成字符串而非 int：颜色带 # 前缀可读，也便于用户手改后仍能解析；
 * 解析失败回落到 [OrdinalStyle.DEFAULT]，不让坏数据把坐标层搞崩。
 */
object OrdinalStyleStore {

    private const val PREFS = "ordinal_style"
    private const val KEY_COLOR = "color"
    private const val KEY_SIZE = "size_sp"
    private const val KEY_GAP_DP = "gap_dp"

    fun load(context: Context): OrdinalStyle {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return OrdinalStyle(
            color = parseColor(prefs.getString(KEY_COLOR, null), OrdinalStyle.DEFAULT.color),
            sizeSp = prefs.getFloat(KEY_SIZE, OrdinalStyle.DEFAULT.sizeSp)
                .coerceIn(OrdinalStyle.MIN_SIZE_SP, OrdinalStyle.MAX_SIZE_SP),
            gapDp = prefs.getFloat(KEY_GAP_DP, OrdinalStyle.DEFAULT_GAP_DP)
                .coerceIn(0f, OrdinalStyle.MAX_GAP_DP),
        )
    }

    fun save(context: Context, style: OrdinalStyle) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COLOR, toHex(style.color))
            .putFloat(KEY_SIZE, style.sizeSp)
            .putFloat(KEY_GAP_DP, style.gapDp)
            .apply()
    }

    /** 复位到默认值。 */
    fun reset(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun toHex(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    private fun parseColor(text: String?, fallback: Int): Int {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        return try {
            Color.parseColor(raw)
        } catch (t: Throwable) {
            fallback
        }
    }
}

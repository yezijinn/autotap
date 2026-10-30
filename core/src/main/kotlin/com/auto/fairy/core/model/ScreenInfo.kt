package com.auto.fairy.core.model

/** 录制时的屏幕参数，用于归一化校验与导入时的宽高比提示。 */
data class ScreenInfo(
    val w: Int,
    val h: Int,
    val dpi: Int,
    val rot: Int,
) {
    init {
        require(w > 0 && h > 0) { "屏幕尺寸非法: ${w}x$h" }
        require(dpi >= 0) { "dpi 非法: $dpi" }
    }

    companion object {
        /** 未知旋转（旧脚本没写 rot）。任何一边未知就不比旋转，避免把历史脚本判死。 */
        const val ROT_UNKNOWN: Int = -1

        @JvmField
        val DEFAULT: ScreenInfo = ScreenInfo(w = 1080, h = 2400, dpi = 420, rot = ROT_UNKNOWN)
    }
}

package com.auto.fairy

import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import com.auto.fairy.core.model.ScreenInfo

/** 屏幕参数工具：录制归一化坐标与脚本元信息都以此处为准。 */
object ScreenMetrics {

    // ── 浮层卡片尺寸 ──────────────────────────────────────
    // 三个模态浮层（脚本列表 / 诊断·保活·设置面板 / 编辑器）共用同一组比例，
    // 曾是三处各写一份 0.9f/0.7f，改一处忘另两处就会「面板和编辑器不一样宽」。
    const val CARD_WIDTH_RATIO = 0.9f
    const val CARD_HEIGHT_RATIO = 0.7f

    /** 真实分辨率（含状态栏/导航栏区域，与 dispatchGesture 的坐标系一致）。 */
    fun size(context: Context): Point {
        val wm = context.getSystemService(WindowManager::class.java)
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        return point
    }

    fun rotation(context: Context): Int {
        val wm = context.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        return wm.defaultDisplay.rotation
    }

    fun screenInfo(context: Context): ScreenInfo {
        val size = size(context)
        return ScreenInfo(
            w = size.x,
            h = size.y,
            dpi = context.resources.displayMetrics.densityDpi,
            rot = rotation(context),
        )
    }

    /**
     * 脚本录制时的屏幕与当前屏幕是否「同一块」——方向、旋转相同且宽高比接近。
     *
     * 归一化坐标只有配上录制时的屏幕尺寸才有意义：竖屏 1440×3200 录的点，
     * 拿到横屏 3200×1440 上会横向拉宽 2.2 倍、纵向压扁 2.2 倍，落到别的控件上。
     * 用户看着就是「乱点」，而且不会自己联想到是方向变了。
     *
     * 旋转也必须比：180° 反转时长边仍在同一条轴上，宽高比一模一样，
     * 但整块画面上下一翻，归一化坐标全靠上下颠倒——只看宽高比完全拦不住。
     *
     * 返回 null 表示可以播；否则返回一句可读的差异说明。
     * 宽高比容差 8%：吸收状态栏/导航栏在不同 ROM 上占掉的高度，不至于误判。
     */
    fun mismatch(context: Context, script: ScreenInfo): String? {
        val now = screenInfo(context)
        return mismatchOf(
            script.w,
            script.h,
            now.w,
            now.h,
            script.rot,
            now.rot,
            script.dpi,
            now.dpi,
        )
    }

    /**
     * [mismatch] 的纯逻辑部分：只比对两组尺寸、旋转与 dpi，便于单测。
     *
     * 判定顺序：方向（谁更长）→ 旋转 → 宽高比 → dp 宽度。方向反了直接拒绝；方向一致但旋转
     * 不同也拒绝（180° 反转宽高比完全一致）；旋转一致但比例差 >8% 同样拒绝
     * （折叠屏展开、分屏）。旧脚本没记尺寸（全 0）或没记旋转（[ScreenInfo.ROT_UNKNOWN]）
     * 时不拦，避免把历史脚本判死。
     *
     * dp 宽度（`w × 160 / dpi`）是最后一道：分辨率与 dpi 同向变化时宽高比可以一模一样，
     * 但控件按 dp 排版，dp 视口变了就整体重排（实测 1440×3200@560 → 1080×2400@560，
     * dp 宽度 411→308，宽高比 0.45=0.45，旧判据放行）。反过来，dp 宽度一致而分辨率不同
     * 是安全的：归一化坐标与像素无关，只与排版尺度有关，这种必须放行。
     */
    fun mismatchOf(
        scriptW: Int,
        scriptH: Int,
        nowW: Int,
        nowH: Int,
        scriptRot: Int = ScreenInfo.ROT_UNKNOWN,
        nowRot: Int = ScreenInfo.ROT_UNKNOWN,
        scriptDpi: Int = 0,
        nowDpi: Int = 0,
    ): String? {
        if (scriptW <= 0 || scriptH <= 0 || nowW <= 0 || nowH <= 0) return null
        val shape = if (scriptRot >= 0 && nowRot >= 0) {
            "$scriptW×$scriptH@${scriptRot * 90}° → $nowW×$nowH@${nowRot * 90}°"
        } else {
            "$scriptW×$scriptH → $nowW×$nowH"
        }
        val wasPortrait = scriptH >= scriptW
        val isPortrait = nowH >= nowW
        if (wasPortrait != isPortrait) return shape
        if (rotationDiffers(scriptRot, nowRot)) return shape
        val wasRatio = scriptW.toDouble() / scriptH
        val nowRatio = nowW.toDouble() / nowH
        if (kotlin.math.abs(wasRatio - nowRatio) / wasRatio > 0.08) return shape
        // dpi 未知（旧脚本）不判；一边知道一边不知道也不判，宁可放过不误杀。
        if (scriptDpi > 0 && nowDpi > 0) {
            val wasDp = scriptW * 160.0 / scriptDpi
            val nowDp = nowW * 160.0 / nowDpi
            if (kotlin.math.abs(wasDp - nowDp) / wasDp > 0.08) {
                // 用户只认识「显示大小」，dp 是内部量；分辨率也带上，同分辨率时一眼看出差在密度
                return "显示大小 ${wasDp.toInt()}dp → ${nowDp.toInt()}dp（$scriptW×$scriptH → $nowW×$nowH）"
            }
        }
        return null
    }

    /** 旋转是否不同：任一侧未知（-1）就不判，旧脚本因此不会被拦。 */
    fun rotationDiffers(scriptRot: Int, nowRot: Int): Boolean =
        scriptRot >= 0 && nowRot >= 0 && scriptRot != nowRot
}

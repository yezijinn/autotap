package com.auto.fairy

import com.auto.fairy.core.model.ScreenInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕失配判定：脚本录制时的屏幕与当前屏幕不是同一块时，归一化坐标会错位。
 *
 * 只锁纯逻辑（传入两组尺寸），不碰 Android 的 display。
 */
class ScreenMismatchTest {

    /** 同尺寸同方向：可播。 */
    @Test
    fun sameScreenIsFine() {
        assertNull(check(1440, 3200, 1440, 3200))
    }

    /** 竖屏录、竖屏放，只有状态栏高度差：容差内，可播。 */
    @Test
    fun smallRatioDriftTolerated() {
        // 1440×3200 = 0.45；1440×3040 ≈ 0.4736，差 5.2%
        assertNull(check(1440, 3200, 1440, 3040))
    }

    /** 竖屏录、横屏放：必须拒绝（实测到的真实错位）。 */
    @Test
    fun portraitScriptOnLandscapeRejected() {
        assertNotNull(check(1440, 3200, 3200, 1440))
    }

    /** 横屏录、竖屏放：同样拒绝。 */
    @Test
    fun landscapeScriptOnPortraitRejected() {
        assertNotNull(check(3200, 1440, 1440, 3200))
    }

    /** 同方向但宽高比差很多（折叠屏展开）：拒绝。 */
    @Test
    fun differentAspectRejected() {
        // 1440×3200 = 0.45  vs  1440×2400 = 0.60，差 33%
        assertNotNull(check(1440, 3200, 1440, 2400))
    }

    /** 分辨率换了但宽高比一致（2K 屏放 1080p 脚本）：可播，归一化坐标本就与像素无关。 */
    @Test
    fun sameAspectDifferentResolutionFine() {
        assertNull(check(1080, 2400, 1440, 3200))
    }

    /** 旧脚本没记屏幕尺寸（全 0）：不拦，避免把历史脚本全判死。 */
    @Test
    fun unknownScreenNotBlocked() {
        assertNull(check(0, 0, 1440, 3200))
    }

    /** 返回的说明里要带上两端尺寸，用户才知道差在哪。 */
    @Test
    fun messageCarriesBothSizes() {
        val why = check(1440, 3200, 3200, 1440)!!
        assertEquals(true, why.contains("1440×3200"))
        assertEquals(true, why.contains("3200×1440"))
    }

    /** 旋转相同、尺寸相同：可播。 */
    @Test
    fun sameRotationAllowed() {
        assertNull(check(1440, 3200, 1440, 3200, scriptRot = 1, nowRot = 1))
        assertNull(check(1440, 3200, 1440, 3200, scriptRot = 0, nowRot = 0))
    }

    /**
     * 0° 与 180°：长边仍在同一条轴上，宽高比一模一样，只看比例完全拦不住。
     * 画面上下一翻，归一化坐标整体颠倒，必须拒绝。
     */
    @Test
    fun flippedRotationRejected() {
        assertNotNull(check(1440, 3200, 1440, 3200, scriptRot = 0, nowRot = 2))
        assertNotNull(check(1440, 3200, 1440, 3200, scriptRot = 2, nowRot = 0))
    }

    /** 90° 与 270°：同样是镜像关系，同样拒绝。 */
    @Test
    fun sidewaysFlippedRotationRejected() {
        assertNotNull(check(1440, 3200, 1440, 3200, scriptRot = 1, nowRot = 3))
        assertNotNull(check(1440, 3200, 1440, 3200, scriptRot = 3, nowRot = 1))
    }

    /** 旧脚本没记旋转：不能因为「不知道」就拒绝，历史脚本必须还能播。 */
    @Test
    fun unknownRotationNotBlocked() {
        assertNull(check(1440, 3200, 1440, 3200, scriptRot = -1, nowRot = 0))
        assertNull(check(1440, 3200, 1440, 3200, scriptRot = 2, nowRot = -1))
    }

    /** 两端旋转都已知时，提示里要写出角度，用户才知道差在哪。 */
    @Test
    fun messageCarriesRotation() {
        val why = check(1440, 3200, 1440, 3200, scriptRot = 0, nowRot = 2)!!
        assertTrue(why.contains("@0°"))
        assertTrue(why.contains("@180°"))
    }

    /**
     * 宽高比相同但 dp 视口差 25%：必须拒绝。
     * 1440×3200@560 = 411dp vs 1080×2400@560 = 308dp，控件会重排。
     */
    @Test
    fun sameAspectDifferentDpRejected() {
        val why = check(1440, 3200, 1080, 2400, sdpi = 560, ndpi = 560)!!
        assertTrue(why.contains("411dp"))
        assertTrue(why.contains("308dp"))
        // dp 是内部量，文案里必须点明它说的是「显示大小」，否则同分辨率时读起来自相矛盾
        assertTrue(why.contains("显示大小"))
    }

    /**
     * 同一块屏、只改密度 560→520（411dp → 443dp，差 7.7%）：带内，必须放行。
     * 400dp 与 411dp（2.8%）这类常见组合也落在带内；8% 一旦被改小，这条先红。
     */
    @Test
    fun dpDriftInsideBandTolerated() {
        assertNull(check(1440, 3200, 1440, 3200, sdpi = 560, ndpi = 520))
    }

    /** 同一块屏、密度 560→518（411dp → 444dp，差 8.1%）：出带，必须拒绝，否则容差放大也没人管。 */
    @Test
    fun dpDriftOutsideBandRejected() {
        assertNotNull(check(1440, 3200, 1440, 3200, sdpi = 560, ndpi = 518))
    }

    /**
     * dp 宽度一致而分辨率不同：必须放行。
     * 411dp 都是 411dp，归一化坐标与像素无关，排版尺度不变就不该拦。
     */
    @Test
    fun sameDpDifferentResolutionFine() {
        assertNull(check(1080, 2400, 1440, 3200, sdpi = 420, ndpi = 560))
    }

    /** 只差状态栏高度：dp 宽度一致，仍可播。 */
    @Test
    fun statusBarOnlyStillFine() {
        assertNull(check(1440, 3200, 1440, 3040, sdpi = 560, ndpi = 560))
    }

    /** 起播校验与它的纯逻辑入口同一判据；播放中止另有严格判据，见下文。 */
    @Test
    fun startupCheckMatchesPureLogic() {
        val script = ScreenInfo(1440, 3200, 560, 0)
        val now = ScreenInfo(1440, 3200, 560, 2)
        assertNotNull(ScreenMetrics.mismatch(script, now))
        assertEquals(
            ScreenMetrics.mismatchOf(1440, 3200, 1440, 3200, 0, 2, 560, 560),
            ScreenMetrics.mismatch(script, now),
        )
    }

    // ── 播放中的严格快照判定 ────────────────────────────────
    // 起播门吃 8% 容差；播放中坐标已按起播那块屏算好，任何真实变化都必须中止。

    /** 高度少 160px（5.2%，落在起播容差内）：起播放行，播放中必须中止。 */
    @Test
    fun playbackRejectsChangeInsideStartupTolerance() {
        assertNull(check(1440, 3200, 1440, 3040))
        assertNotNull(strict(1440, 3200, 1440, 3040))
    }

    /** 密度 560→520（dp 差 7.7%，起播容差内）：播放中控件会重排，必须中止。 */
    @Test
    fun playbackRejectsDensityChangeInsideStartupTolerance() {
        assertNull(check(1440, 3200, 1440, 3200, sdpi = 560, ndpi = 520))
        assertNotNull(strict(1440, 3200, 1440, 3200, beforeDpi = 560, nowDpi = 520))
    }

    /** 尺寸没变、只转了 180°：严格判据必须看出来。 */
    @Test
    fun playbackRejectsRotationChange() {
        assertNotNull(strict(1440, 3200, 1440, 3200, beforeRot = 0, nowRot = 2))
        assertNotNull(strict(1440, 3200, 1440, 3200, beforeRot = 1, nowRot = 3))
    }

    /** 同一块屏：严格判据不误报。 */
    @Test
    fun playbackSameSnapshotIsFine() {
        assertNull(strict(1440, 3200, 1440, 3200, beforeRot = 1, nowRot = 1, beforeDpi = 560, nowDpi = 560))
    }

    /** 值未知时不判（与起播门同口径）：不能把「不知道」当成「变了」。 */
    @Test
    fun playbackUnknownValuesNotBlocked() {
        assertNull(strict(1440, 3200, 1440, 3200))
        assertNull(strict(1440, 3200, 1440, 3200, beforeDpi = 0, nowDpi = 560))
        assertNull(strict(1440, 3200, 1440, 3200, beforeRot = -1, nowRot = 2))
    }

    /** 说明要逐项写出变了的参数，用户才知道是转屏、分屏还是改了显示大小。 */
    @Test
    fun playbackMessageNamesWhatChanged() {
        val size = strict(1440, 3200, 1440, 3040)!!
        assertTrue(size.contains("尺寸"))
        assertTrue(size.contains("1440×3200"))
        assertTrue(size.contains("1440×3040"))

        val rot = strict(1440, 3200, 1440, 3200, beforeRot = 0, nowRot = 2)!!
        assertTrue(rot.contains("旋转"))
        assertTrue(rot.contains("0°"))
        assertTrue(rot.contains("180°"))

        val dpi = strict(1440, 3200, 1440, 3200, beforeDpi = 560, nowDpi = 520)!!
        assertTrue(dpi.contains("显示密度"))
        assertTrue(dpi.contains("560"))
        assertTrue(dpi.contains("520"))
    }

    /** 快照入口与纯逻辑入口同一判据。 */
    @Test
    fun playbackSnapshotEntryMatchesPureLogic() {
        val before = ScreenInfo(1440, 3200, 560, 0)
        val now = ScreenInfo(1440, 3040, 560, 0)
        assertEquals(
            ScreenMetrics.strictChangeOf(1440, 3200, 1440, 3040, 0, 0, 560, 560),
            ScreenMetrics.strictChange(before, now),
        )
    }

    private fun check(
        sw: Int,
        sh: Int,
        nw: Int,
        nh: Int,
        scriptRot: Int = ScreenInfo.ROT_UNKNOWN,
        nowRot: Int = ScreenInfo.ROT_UNKNOWN,
        sdpi: Int = 0,
        ndpi: Int = 0,
    ): String? = ScreenMetrics.mismatchOf(
        scriptW = sw,
        scriptH = sh,
        nowW = nw,
        nowH = nh,
        scriptRot = scriptRot,
        nowRot = nowRot,
        scriptDpi = sdpi,
        nowDpi = ndpi,
    )

    private fun strict(
        bw: Int,
        bh: Int,
        nw: Int,
        nh: Int,
        beforeRot: Int = ScreenInfo.ROT_UNKNOWN,
        nowRot: Int = ScreenInfo.ROT_UNKNOWN,
        beforeDpi: Int = 0,
        nowDpi: Int = 0,
    ): String? = ScreenMetrics.strictChangeOf(
        beforeW = bw,
        beforeH = bh,
        nowW = nw,
        nowH = nh,
        beforeRot = beforeRot,
        nowRot = nowRot,
        beforeDpi = beforeDpi,
        nowDpi = nowDpi,
    )
}

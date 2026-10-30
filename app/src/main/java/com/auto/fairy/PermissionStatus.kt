package com.auto.fairy

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** 保活相关权限的实时查询，诊断页与保活页共用。 */
object PermissionStatus {

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /**
     * 订阅「无障碍服务连接状态变化」。
     *
     * 系统没有提供「权限被改」的广播：用户可能在设置页里开关服务，也可能被 adb
     * 直接改 secure 设置。服务连接/断开是唯一可靠的信号点，主界面据此实时刷新横幅。
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 由 [AutoFairyAccessibilityService] 在连上/断开时调用（主线程）。 */
    fun notifyA11yChanged() {
        listeners.forEach { it() }
    }

    /**
     * 无障碍开关是否开着（界面横幅与诊断页据此显示「已开启 / 未开启」）。
     *
     * 判据有三层，任一成立即算开着：
     * 1. 注入后端已经挂在当前进程上（[PlaybackController.isBackendReady]）；
     * 2. 系统已启用列表里有本服务；
     * 3. 无障碍总开关开着，且 [`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`]
     *    里写着本服务。
     *
     * 为什么不能只看第 2 条：MuMu/API 32 实测出现过「服务已启用、已绑定、注入确实生效，
     * 但 [AccessibilityManager.getEnabledAccessibilityServiceList] 不返回它」的状态
     * （服务崩过几次后系统会把它从列表里摘掉，重装/重启才恢复）。此时横幅会一直写着
     * 「无障碍服务未开启，无法执行点击」，把用户引到设置页去改一个本来就对的开关。
     * 后端在手上才是「能不能点」的真凭据。
     *
     * 为什么还要第 3 条：同一个「列表漏报」也会把 `PlaybackController` 的后端一起挡在门外
     * （服务没连上，就没有 delegate），于是第 1、2 条同时为假，横幅照样谎报未开启。
     * 安全设置串是用户拨动开关时系统亲手写下的，是「开关开着」的唯一可靠来源。
     */
    fun isA11yEnabled(context: Context): Boolean {
        if (PlaybackController.isBackendReady()) return true
        return isListedBySystem(context) || isSwitchedOn(context)
    }

    /**
     * 服务是否真的连上了（能执行点击）。
     *
     * 与 [isA11yEnabled] 分开：开关开着但服务没连上时，横幅要说「未连接，请关闭后再开启一次」，
     * 而不是让用户去开一个已经开着的开关。
     */
    fun isA11yConnected(context: Context): Boolean =
        PlaybackController.isBackendReady() || isListedBySystem(context)

    private fun isListedBySystem(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val target = ComponentName(context, AutoFairyAccessibilityService::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == target.packageName && info.name == target.className
            }
    }

    /** 无障碍总开关 + 已启用服务串（系统写的，不受 [isListedBySystem] 漏报影响）。 */
    private fun isSwitchedOn(context: Context): Boolean {
        val resolver = context.contentResolver
        if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false
        }
        val target = ComponentName(context, AutoFairyAccessibilityService::class.java)
        return enabledServiceListed(
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            target.packageName,
            target.className,
        )
    }

    fun isOverlayEnabled(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** null 表示系统未提供该能力（极少数精简 ROM）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean? {
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** null 表示当前系统版本没有该接口（< API 28）。 */
    fun isBackgroundRestricted(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return manager.isBackgroundRestricted
    }
}

/**
 * 「已启用的无障碍服务」串里是否含指定服务。
 *
 * 串形如 `pkg/cls:pkg/cls`。同一个组件有两种合法写法：设置页写
 * `ComponentName.flattenToString`（全类名），用 `flattenToShortString` 的写入方
 * （adb 手写、部分实现）写短形式 `pkg/.Cls`。实测系统两种都认（短形式照样把服务
 * 绑起来），只是落盘的值不同，所以两种都收：只认全类名时，短形式那条会被判成
 * 「没开」，又回到谎报。
 * 纯字符串判定，不碰 Android 类，因此可以直接跑单测。
 */
internal fun enabledServiceListed(flat: String?, packageName: String, className: String): Boolean {
    if (flat.isNullOrEmpty()) return false
    val full = "$packageName/$className"
    val short = "$packageName/.${className.removePrefix("$packageName.")}"
    return flat.split(':').any { item ->
        val entry = item.trim()
        entry == full || entry == short
    }
}

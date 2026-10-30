package com.auto.fairy

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.auto.fairy.overlay.CoordinateMarkersController

/**
 * 无障碍服务：进程常驻锚点 + 注入后端宿主（设计方案 4.1）。
 *
 * v1.0 不做控件点击/读屏，onAccessibilityEvent 留空；服务唯一职责是把
 * [A11yBackend] 注册进 [PlaybackController]，并在存活期间让进程处于活跃状态。
 *
 * 另外承担音量键停止：配置里声明了 flagRequestFilterKeyEvents，按键是否被接管由
 * [onKeyEvent] 逐次决定——**只有脚本正在运行**时才消费并停止，其余情况一律返回 false，
 * 音量键照常交给系统，不会把系统音量锁死，也不会在空闲时被本应用拦截。
 */
class AutoFairyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoFairyA11y"

        /** 当前连接的服务实例：坐标层触摸窗要用它的窗口管理器挂无障碍覆盖层。 */
        @Volatile
        var instance: AutoFairyAccessibilityService? = null
            private set
    }

    private var backend: A11yBackend? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 读取一次配置资源：shrinkResources 只按 Manifest 的 meta-data 引用判断不到它，
        // 若不在这里建立代码引用，release 包会把 xml 配置删掉，系统随即丢弃本服务。
        // 取到的 XmlResourceParser 当场关掉即可——要的只是"被引用"这个事实。
        resources.getXml(R.xml.accessibility_service_config).close()
        val created = A11yBackend(this)
        backend = created
        PlaybackController.attachBackend(created)
        // 服务连接不挂出任何浮层：小窗只由「激活」唤起。
        // 坐标层只在会话还在（小窗挂着）时补挂一次，重连不会凭空画出红点。
        CoordinateMarkersController.restore(this)
        // 主界面横幅按连接状态实时刷新（系统没有权限变更广播）
        PermissionStatus.notifyA11yChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // v1.0 不消费无障碍事件
    }

    override fun onInterrupt() {
        // 无长任务，无需中断处理
    }

    /**
     * 音量键 = **仅在本应用正在回放时** 的紧急停止，音量加、减效果相同。
     *
     * 运行之外一律返回 false：不消费、不弹提示、不改任何状态，音量键照常调节系统音量。
     * 只处理首次按下（ACTION_DOWN 且无长按重复），长按调音量不会被误吞。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_UP
        ) {
            return false
        }
        // 模拟器等无物理音量键的环境里，adb 注入的按键事件源是 injected，
        // 系统 KeyboardInterceptor 不会转给无障碍服务，此时本方法根本不会被调用。
        // 真机排查靠下面这行日志确认回调是否到达。
        Log.i(TAG, "onKeyEvent key=${event.keyCode} action=${event.action}")
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (!AppSettings.isVolumeKeyEnabled(this)) return false
        // 运行外的按键一律交还系统，判定只在 stopFromVolumeKey 一处
        return PlaybackController.stopFromVolumeKey()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        if (instance === this) instance = null
        val current = backend ?: return
        backend = null
        PlaybackController.detachBackend(current)
        current.close()
        // 悬浮窗保留：后端失效时按规格显示「已失效」红态，而不是把控制器一起撤掉
        // 主界面横幅要跟着回到「未开启」
        PermissionStatus.notifyA11yChanged()
    }
}

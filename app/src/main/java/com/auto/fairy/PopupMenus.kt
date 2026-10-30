package com.auto.fairy

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 轻量弹层菜单：直接用宿主根布局里的覆盖层实现，不开新窗口。
 *
 * 不用 PopupWindow 的原因：在无障碍/悬浮窗环境里，PopupWindow 抢焦点会把
 * 同一次抬指事件判成「窗口外点击」而立即关闭，真机上表现为菜单一闪而过。
 *
 * M5 起支持两种宿主：Activity（找 root_main）与 WindowManager 浮层（直接传浮层根 View），
 * 两者共用同一套 `menu_layer` / `menu_container` id 与视觉。
 */
object PopupMenus {

    fun show(activity: Activity, anchor: View, items: List<Pair<String, () -> Unit>>) {
        val root = activity.findViewById<View>(R.id.root_main) ?: return
        show(root, anchor, items)
    }

    /** 浮层宿主：root 内需含 menu_layer + menu_container（见 overlay_script_list.xml）。 */
    fun show(root: View, anchor: View, items: List<Pair<String, () -> Unit>>) {
        if (items.isEmpty()) return
        // 菜单项全是即时动作：脚本行的 激活/编辑/导出/删除，第二层「删除」直接落盘，
        // 表头的 诊断/保活/设置，FAB 的 新建/录制/导入。两种宿主（Activity / 浮层）共用这里。
        PlaybackController.pauseForModal()
        val layer = root.findViewById<FrameLayout>(R.id.menu_layer) ?: return
        val container = root.findViewById<LinearLayout>(R.id.menu_container) ?: return
        // 浮层宿主用的是 application context，自身不带主题；套一层 AppCompat 主题
        // 才能解析 item_popup_menu 里的 ?attr/selectableItemBackground
        val themed = android.view.ContextThemeWrapper(root.context, R.style.Theme_AutoFairy)
        val inflater = LayoutInflater.from(themed)

        container.removeAllViews()
        for ((label, action) in items) {
            val item = inflater.inflate(R.layout.item_popup_menu, container, false) as TextView
            item.text = label
            item.setOnClickListener {
                // 打开菜单后的头一片注入点击照旧落下（暂停要等引擎到片边界，最坏一片 400ms）：
                // 这一小段点了也不执行，否则一次误击就把脚本删了。人点不到刚画出来的菜单项，
                // 因此这段丢弃不会误伤真实操作。
                if (PlaybackController.injecting) return@setOnClickListener
                hide(root)
                action()
            }
            container.addView(item)
        }

        layer.visibility = View.VISIBLE
        layer.setOnClickListener { hide(root) }

        val anchorPos = IntArray(2)
        val rootPos = IntArray(2)
        anchor.getLocationInWindow(anchorPos)
        root.getLocationInWindow(rootPos)
        container.post {
            val width = container.width
            val height = container.height
            val margin = dp(root, 8)
            // 一律换算成 root 内部坐标：浮层窗口与 Activity 的原点不一定在屏幕 (0,0)
            val anchorX = anchorPos[0] - rootPos[0]
            val anchorY = anchorPos[1] - rootPos[1]
            var x = anchorX + anchor.width - width
            var y = anchorY - height - margin
            if (y < 0) y = anchorY + anchor.height + margin
            x = x.coerceIn(margin, (root.width - width - margin).coerceAtLeast(margin))
            y = y.coerceIn(margin, (root.height - height - margin).coerceAtLeast(margin))
            container.translationX = x.toFloat()
            container.translationY = y.toFloat()
        }
    }

    fun hide(activity: Activity) {
        activity.findViewById<FrameLayout>(R.id.menu_layer)?.visibility = View.GONE
    }

    fun hide(root: View) {
        root.findViewById<FrameLayout>(R.id.menu_layer)?.visibility = View.GONE
    }

    fun isShowing(activity: Activity): Boolean =
        activity.findViewById<FrameLayout>(R.id.menu_layer)?.visibility == View.VISIBLE

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()
}

package com.auto.fairy

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import com.auto.fairy.overlay.CoordinateMarkersController
import com.auto.fairy.databinding.OverlayQuickAddBinding

/**
 * 小悬浮窗加号弹出的「追加步骤」选择层（`TYPE_APPLICATION_OVERLAY`）。
 *
 * 为什么用 WindowManager 浮层而不是 Dialog：
 * - [OverlayController] 是只有 application context 的单例，拿不到 Activity 窗口令牌；
 * - 小窗常在别的应用前台时使用，用 Activity 会先切走当前应用（闪一下），
 *   而 `TYPE_APPLICATION_OVERLAY` 直接压在别人上面，和 [OverlayScriptList] 同一机制；
 * - 与列表浮层保持一致的层次与关闭方式（遮罩点击/返回键/取消），用户不用学新交互。
 */
object OverlayQuickAdd {

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var binding: OverlayQuickAddBinding? = null
    private var added = false

    fun open(context: Context) {
        val app = context.applicationContext
        if (added) return
        // 录制是独占触摸的全屏层，叠上去只会互抢事件；脚本列表同为全屏遮罩，
        // 两层叠着用户无法分辨哪层在响应，所以统一先让位。
        if (RecordOverlay.isRecording()) {
            showToast(app, R.string.toast_add_step_recording)
            return
        }
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, R.string.toast_permission_overlay, Toast.LENGTH_LONG).show()
            return
        }
        OverlayScriptList.close()
        // 打开加号层即暂停：注入点击会直接把「点击/滑动」步骤追加进脚本
        PlaybackController.pauseForModal()

        val wm = app.getSystemService(WindowManager::class.java)
        // 浮层窗口必须显式套主题：?attr 与 AppCompat 控件才解析得到
        val themed = android.view.ContextThemeWrapper(app, R.style.Theme_AutoFairy)
        val view = OverlayQuickAddBinding.inflate(android.view.LayoutInflater.from(themed))
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 需要接住返回键，因此不设 FLAG_NOT_FOCUSABLE；触摸由遮罩独占
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        view.quickAddCard.setOnClickListener { }
        // 回放中注入的点击到不了这里：根容器 [InjectShieldLayout] 在 injecting 期间丢弃整串事件
        // （第二十六轮实测：播放中打开本层、脚本点在该层「点击」按钮中心，步数当场 +1）。
        // 取消与返回键照旧。
        view.quickAddScrim.setOnClickListener {
            if (!PlaybackController.injecting) close()
        }
        view.btnQuickAddCancel.setOnClickListener { close() }
        view.btnQuickAddTap.setOnClickListener {
            appendStep(R.string.toast_step_added_tap) {
                Step.Tap(
                    points = listOf(Point(0.5f, 0.5f)),
                    durationMs = DEFAULT_TAP_MS,
                    preDelayMs = Step.PRE_DELAY_DEFAULT,
                    repeat = 1,
                )
            }
        }
        view.btnQuickAddSwipe.setOnClickListener {
            appendStep(R.string.toast_step_added_swipe) {
                Step.Swipe(
                    points = listOf(Point(0.5f, 0.25f), Point(0.5f, 0.75f)),
                    durationMs = DEFAULT_SWIPE_MS,
                    preDelayMs = Step.PRE_DELAY_DEFAULT,
                    repeat = 1,
                )
            }
        }
        view.root.isFocusableInTouchMode = true
        view.root.requestFocus()
        view.root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                close()
                true
            } else {
                false
            }
        }

        // 加号层是满屏遮罩：坐标层的圆点与触摸窗会被它盖住，
        // 先让位，关闭时再恢复（与编辑器/录制/面板同一套互斥）。
        CoordinateMarkersController.setSuppressed(app, REASON_QUICK_ADD, true)
        try {
            wm.addView(view.root, layoutParams)
            added = true
        } catch (t: Throwable) {
            CoordinateMarkersController.setSuppressed(app, REASON_QUICK_ADD, false)
            binding = null
            showToast(app, R.string.toast_overlay_add_failed)
            return
        }

        appContext = app
        windowManager = wm
        binding = view
    }

    fun close() {
        val view = binding ?: return
        if (added) runCatching { windowManager?.removeView(view.root) }
        added = false
        appContext?.let {
            CoordinateMarkersController.setSuppressed(it, REASON_QUICK_ADD, false)
        }
        binding = null
        windowManager = null
        appContext = null
    }

    // ── 追加步骤 ────────────────────────────────────────────

    /**
     * 把新步骤追加到「当前可编辑脚本」末尾。
     *
     * 当前脚本口径与应用内其他入口一致：播放中的脚本 → 上次播放 → 最新脚本；
     * 一个都没有时提示并打开脚本列表（那里的「新建」是唯一新建入口）。
     */
    private fun appendStep(toastRes: Int, build: () -> Step) {
        val app = appContext ?: return
        val repo = AutoFairyApp.of(app).repository
        val script = currentScript(repo)
        if (script == null) {
            close()
            // 一个脚本都没有 → 引导新建；有脚本但当前目标读不出 → 说清楚是「不可用」，
            // 否则「还没有脚本」会让用户以为文件丢了
            val anyScript = repo.list(includeBroken = true).isNotEmpty()
            val message = if (anyScript) R.string.toast_add_step_unavailable else R.string.toast_add_step_no_script
            showToast(app, message)
            OverlayScriptList.open(app)
            return
        }
        if (repo.saveOrNull(script.copy(steps = script.steps + build())) == null) {
            close()
            showToast(app, R.string.toast_save_failed)
            return
        }
        close()
        showToast(app, toastRes)
    }

    private fun currentScript(repo: ScriptRepository): Script? {
        // 与播放目标同一口径，但**不做列表首项回落**：追加是写盘动作，目标必须是用户
        // 明确选过的那条（播放/暂停中 → 上次激活）。目标失效时宁可不追加并让用户去列表重选，
        // 也不能静默写进另一条脚本——第二十六轮实测踩到：测试脚本删掉后回落到了用户脚本，
        // 一次「点击」就把 391 字节的脚本写成 716 字节。
        val id = PlaybackController.playTargetId ?: return null
        return repo.load(id)
    }

    private const val DEFAULT_TAP_MS = 60L
    private const val DEFAULT_SWIPE_MS = 300L

    /** 坐标层互斥来源标识，与其它来源共用同一套 suppressors 机制。 */
    private const val REASON_QUICK_ADD = "quick_add"
}

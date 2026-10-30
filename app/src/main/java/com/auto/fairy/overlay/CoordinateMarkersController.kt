package com.auto.fairy.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.auto.fairy.AutoFairyApp
import com.auto.fairy.OverlayController
import com.auto.fairy.PlaybackController
import com.auto.fairy.R
import com.auto.fairy.ScreenMetrics
import com.auto.fairy.ScriptRepository
import com.auto.fairy.showToast
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.runtime.PlaybackEngine
import com.auto.fairy.databinding.OverlayCoordMarkersBinding
import kotlin.math.abs

/**
 * 坐标标记层控制器（进程级单例）。
 *
 * 两个窗口：
 * - 绘制层：全屏，恒带 FLAG_NOT_TOUCHABLE，只画圆点与序号，绝不挡底层操作；
 * - 触摸窗：可拖开启时，每个圆点配一个 [touchSpan] 见方的小窗，只有点中圆点才吃触摸；
 *   播放期间一个都不建（注入的点击会先命中它们，把脚本自己的点击吞掉）；
 *
 * 屏幕上不放任何工具条：显示与可拖两个开关只放设置页，
 * 避免同一状态有两处控制入口。
 *
 * 显示开关与可拖开关都持久化，默认都开：会话期间坐标要在任何页面（含桌面、其它应用）
 * 都保持可见，这是用户明确要求的行为。两个开关只由设置页写；激活/✕ 只是收放本会话
 * 的窗口，不改偏好（[hide]）。
 *
 * 不变式：**没有激活中的脚本会话就不画**。会话的真源是迷你条是否在屏上
 * （[OverlayController.isShowing]），[restore] 在此把关；激活路径走 [show] 直挂。
 * 理由：进程级常驻会让主页凭空冒出红点（实测：只有列表和红点、没有迷你条），
 * 且空闲时目标会回落「上次播过」「列表首项」，画出来的未必是用户要跑的脚本。
 */
object CoordinateMarkersController {

    private const val PREFS = "coord_layer"
    /** 新键：与旧版的 coord_layer_on 分开，默认值 true 才能对老用户生效。 */
    private const val KEY_ON = "coord_visible"
    private const val KEY_MOVE = "coord_draggable"
    private const val DRAG_SLOP_DP = 8f
    private const val SCREEN_MARGIN_DP = 8f

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var markersRoot: View? = null
    private var markers: CoordinateMarkers? = null
    private var markersParams: WindowManager.LayoutParams? = null
    private var repository: ScriptRepository? = null
    private var script: Script? = null
    private var added = false
    private var moveEnabled = true

    /** 每个圆点的触摸窗（可拖开启时才有）。 */
    private val touches = ArrayList<View>()

    /** 触摸窗用的窗口管理器：优先无障碍服务（可挂 TYPE_ACCESSIBILITY_OVERLAY，硬件触摸才到得了）。 */
    private var touchWindowManager: WindowManager? = null

    /**
     * 播放中：一个触摸窗都不留。
     *
     * 注入的点击与手指共用同一套命中测试，圆点窗压在最上面就会先把 DOWN 吃掉，
     * 脚本点的正是这些位置，结果是一条脚本自己点不动自己。判断必须发生在
     * 提交 Start 之前（见 [PlaybackController.play]），所以这里是一个显式开关，
     * 不能只靠读播放状态——引擎线程起步比窗口事务更快。
     */
    private var playbackActive = false

    /** 触摸窗当前的几何指纹：没变就不重建，避免播放状态刷新时反复增删几十个窗口。 */
    private var touchKey = 0

    /** 已经提示过「点太多」的脚本 id：同一个脚本只提醒一次，别每次刷新都弹。 */
    private var overflowNotifiedId: String? = null

    /**
     * 屏幕上其它悬浮窗的占位（迷你条等）。触摸窗按它避让：
     * 同类型窗口谁后 add 谁在上，圆点窗若压在迷你条上，播放/停止键就点不到了。
     */
    private val obstacles = LinkedHashMap<String, () -> Rect?>()

    /** 互斥来源（编辑器 / 列表 / 录制 / 取坐标）：非空时整层隐藏。 */
    private val suppressors = HashSet<String>()

    private val handler = Handler(Looper.getMainLooper())

    private val playbackListener = object : PlaybackController.Listener {
        override fun onPlaybackState(state: PlaybackEngine.State, scriptId: String?) {
            if (added) refresh()
            when (state) {
                PlaybackEngine.State.RUNNING -> startStepSync()
                // 停止 / 暂停：立即收箭头（IDLE 还要把十字归零），不等下一次步骤事件。
                else -> {
                    handler.removeCallbacks(stepSync)
                    if (added) updateActiveStep()
                }
            }
        }
    }

    /**
     * 起播后把当前步推进到坐标层。
     *
     * 单步脚本没有任何「步骤切换」事件，而刚起播时引擎时钟还没推开、`cursorStepIndex()`
     * 会返回 0；只推进一次的话整场都不会有十字高亮与滑动箭头（实测 10s 单步滑动无箭头）。
     * 这里短间隔重试，直到取到有效步号或播放结束。
     */
    private val stepSync = object : Runnable {
        private var tries = 0

        override fun run() {
            if (!added) return
            val ord = PlaybackController.cursorStepIndex()
            updateActiveStep()
            if (ord == 0 && tries < 20 && PlaybackController.state == PlaybackEngine.State.RUNNING) {
                tries++
                handler.postDelayed(this, 100L)
            } else {
                tries = 0
            }
        }
    }

    private fun startStepSync() {
        handler.removeCallbacks(stepSync)
        handler.postDelayed(stepSync, 120L)
    }

    private val repositoryListener: () -> Unit = { if (added) { script = null; refresh() } }

    // ── 开关（设置页读写） ──────────────────────────────────

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, true)

    fun isMoveEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MOVE, true)

    /** 注册一个需要避让的悬浮窗（迷你条）。provider 返回屏幕像素矩形，无窗口时返回 null。 */
    fun registerObstacle(key: String, provider: () -> Rect?) {
        obstacles[key] = provider
    }

    /**
     * 设置页「显示坐标标记」：立即生效，关掉即撤层。
     *
     * **只有这里会写显示开关**：激活/✕ 只收放本会话的层（见 [hide]），
     * 不动用户的偏好——用户在设置里关掉的层，不能因为点了一次激活又被打开。
     *
     * 没有激活会话时不挂层，只写偏好 + 一次提示：坐标是「这次脚本的可视化」，
     * 没有脚本就没有可画的东西（实测：设置里打开开关，主页凭空冒出红点）。
     * 下次激活照常按开关恢复。
     */
    fun setOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, enabled).apply()
        if (!enabled) {
            hide()
            return
        }
        // 先判有没有会话：它比「面板压着」更根本，提示也更有用
        if (!OverlayController.isShowing()) {
            // 用调用方的 context：冷启动没挂过层，appContext 是空的，
            // 走 toast() 会被 showToast 的 null 判断静默吞掉
            showToast(context, R.string.coord_need_session)
            return
        }
        // 面板/编辑器还压在上面：此刻挂层会被立刻撤掉，提示等它关闭后自动出现
        if (suppressors.isNotEmpty()) {
            showToast(context, R.string.coord_show_pending)
            return
        }
        show(context)
    }

    /** 设置页「坐标可拖」：立即生效，按新状态重建触摸窗。 */
    fun setMoveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOVE, enabled).apply()
        moveEnabled = enabled
        if (!enabled) {
            markers?.clearDragPreview()
        }
        rebuildTouchWindows()
    }

    /**
     * 按用户开关恢复（激活 / 主页回到前台 / 无障碍重连 / 抑制释放）；
     * 没有激活会话、开关关着、权限缺失都什么都不做。
     *
     * 会话闸门放这里而不是各调用点：这些入口有 4 处以上，逐个加判断必然漏掉一处，
     * 而「没激活也有红点」正是漏出来的症状。真源只有迷你条的在屏记账。
     */
    fun restore(context: Context) {
        // 没激活就没有「当前脚本」这回事：进程冷启动时目标会回落到上次播过甚至列表首项，
        // 画出来的未必是用户要跑的脚本
        if (!OverlayController.isShowing()) return
        if (!isOn(context)) return
        // 防御：窗口被系统摘掉（内存回收、系统清理浮层）后 added 仍为 true，
        // 只认 added 会让坐标层再也回不来；对不上就当作没挂，重新挂一次。
        if (added && markersRoot?.isAttachedToWindow != true) hide()
        show(context)
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 直接挂层。调用方只有 [restore]（会话已由闸门确认）与 [setOn]（已先判会话）。
     * 所以这里**不查** [OverlayController.isShowing]：闸门只约束自动恢复，
     * 挂在这里会把激活路径一起挡掉。
     */
    fun show(context: Context) {
        val app = context.applicationContext
        if (added) return
        if (suppressors.isNotEmpty()) return
        if (!Settings.canDrawOverlays(app)) return

        val repo = AutoFairyApp.of(app).repository
        val wm = app.getSystemService(WindowManager::class.java)
        val screen = ScreenMetrics.size(app)
        val prefs = prefs(app)

        appContext = app
        repository = repo
        windowManager = wm
        moveEnabled = prefs.getBoolean(KEY_MOVE, true)

        // 与 OverlayController 一致：应用 context 不带主题，浮层显式套主题再 inflate
        val themed = ContextThemeWrapper(app, R.style.Theme_AutoFairy)
        val markersBinding = OverlayCoordMarkersBinding.inflate(LayoutInflater.from(themed))

        markersParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 必须 < 系统的「不可信遮挡」上限（0.8），否则 Android 12+ 会判定本层
            // 遮挡了下方应用，把注入的点击整批丢弃（实测：启动器图标点不开，
            // InputDispatcher 记 "Dropping untrusted touch ... obscuring opacity 1.00"）。
            // 满屏窗口哪怕只画几个点，系统也只按窗口 alpha 算遮挡，不看像素。
            alpha = 0.78f
        }

        markersRoot = markersBinding.root
        markers = markersBinding.markersView
        markersBinding.markersView.bind(null, screen.x, screen.y)
        // 旋转/分屏会改变层的实际尺寸：重算归一化映射与触摸窗，否则圆点全部错位
        markersBinding.markersView.onViewSizeChanged = { handler.post { refresh() } }

        try {
            wm.addView(markersBinding.root, markersParams)
            added = true
        } catch (t: Throwable) {
            runCatching { wm.removeView(markersBinding.root) }
            hide()
            return
        }

        PlaybackController.addListener(playbackListener)
        repo.addListener(repositoryListener)
        markers?.setOrdinalStyle(OrdinalStyleStore.load(app))
        refresh()
    }

    /** 设置页改了序号字体：立即应用到绘制层（若在显示）。 */
    fun applyOrdinalStyle(context: Context) {
        markers?.setOrdinalStyle(OrdinalStyleStore.load(context))
    }

    /**
     * 互斥：编辑器 / 列表 / 录制 / 取坐标打开时整层隐藏，关闭后按用户开关恢复。
     *
     * 恢复走 [restore] 而不是 [show]：模态关闭只是副作用，不是「用户又要看坐标」的意图。
     * [restore] 查显示开关与会话闸门，[show] 两个都不查——直接 show 会替用户把
     * 设置里关掉的层挂出来。
     */
    fun setSuppressed(context: Context, reason: String, suppressed: Boolean) {
        val changed = if (suppressed) suppressors.add(reason) else suppressors.remove(reason)
        if (!changed) return
        if (suppressors.isNotEmpty()) {
            if (added) hide()
        } else {
            restore(context)
        }
    }

    // ── 内部：窗口 ──────────────────────────────────────────

    /**
     * 撤掉整层（绘制层 + 触摸窗），**不改显示开关**；没挂窗口时是空操作。
     *
     * 「关掉坐标标记」只能走 [setOn]（唯一写开关的地方）；这里只管本会话的收放，
     * 激活/✕ 不该替用户改偏好。抑制（编辑器/面板/录制/取坐标）与 ✕ 都走到这里：
     * 前者退出抑制时由 [setSuppressed] 调 [restore] 挂回，后者条已不在、闸门不放行。
     */
    fun hide() {
        clearTouches()
        markers?.onViewSizeChanged = null
        PlaybackController.removeListener(playbackListener)
        repository?.removeListener(repositoryListener)
        val wm = windowManager
        markersRoot?.let { runCatching { wm?.removeView(it) } }
        added = false
        touchKey = 0
        markersRoot = null
        markers = null
        markersParams = null
        repository = null
        script = null
    }

    private fun refresh() {
        val view = markers ?: return
        val app = appContext ?: return
        val repo = repository ?: return
        val screen = ScreenMetrics.size(app)
        // 当前脚本：与迷你条、播放目标完全同一口径（[PlaybackController.resolvedTargetId]）。
        // 曾经这里在目标读不出时退到「最近修改」，于是屏幕画着 B 的坐标、按开始跑的却是 A；
        // 目标读不出就应该什么都不画，而不是画另一条。
        val id = PlaybackController.resolvedTargetId()
        // 仓库没变就复用已加载的那份：状态通知（暂停/停止各补 11 次）也会走到这儿，
        // 每次重新读盘 + 解析 JSON 是白耗。仓库变化时 repositoryListener 已清缓存。
        // 注意只省读盘与解析：下面的 bind 每次都要做（屏幕尺寸可能变了），
        // 想连 bind 一起省就会让圆点按旧屏幕尺寸映射 = 错位。
        val current = if (script?.id == id) script else id?.let { repo.load(it) }
        script = current
        view.bind(current, screen.x, screen.y)
        updateActiveStep()
        rebuildTouchWindows()
    }

    /**
     * 回放推进到下一步：由 [PlaybackController] 按步边界调用。
     *
     * 直接调用而不是走 Listener 接口：接口默认方法在 R8 处理后会让未覆盖它的实现类
     * 抛 `AbstractMethodError`（真机复现过），而高亮环本来就只服务坐标层这一处。
     */
    fun onPlaybackStepChanged() {
        if (added) updateActiveStep()
    }

    /** 推进当前执行步：十字按此序号放大；不在执行时清零恢复原尺寸。数字颜色始终不变。 */
    private fun updateActiveStep() {
        val view = markers ?: return
        val st = PlaybackController.state
        when (st) {
            PlaybackEngine.State.IDLE -> {
                // 退出/停止：清滑动箭头（常驻紫线保留），十字归零恢复基础尺寸。
                view.stopSwipeArrow()
                view.setActiveOrdinal(0)
            }
            PlaybackEngine.State.PAUSED -> {
                // 暂停：箭头必须立刻消失（需求/评审硬要求），但十字仍高亮当前步不变。
                // 先按当前步设十字（若是滑动步 setActiveOrdinal 会先尝试起箭头），
                // 再显式清掉箭头，确保「暂停即无箭头、十字保留」。
                view.setActiveOrdinal(PlaybackController.cursorStepIndex())
                view.stopSwipeArrow()
            }
            else -> {
                // RUNNING：按当前步推进；若这一步是滑动，箭头会重新加载并从起点开始走
                // （覆盖「暂停→恢复」后同一 ordinal 再次进入的情形，箭头恢复可见）。
                view.setActiveOrdinal(PlaybackController.cursorStepIndex())
            }
        }
    }

    // ── 圆点触摸窗 ──────────────────────────────────────────

    /** 迷你条移位/关闭后调用：被它压住的圆点窗要重新摆一次。 */
    fun onObstaclesChanged() = rebuildTouchWindows()

    /**
     * 播放开始/结束：开始前**同步**撤掉所有圆点触摸窗，结束后按开关补回。
     *
     * 同步（[WindowManager.removeViewImmediate]）是必须的：注入的第一笔点击
     * 与 [PlaybackController.play] 几乎同一时刻发出，窗口若只排进下一个事务，
     * 这一笔仍会被吃掉（实测：异步撤窗后首个 Tap 依旧不达）。
     *
     * @return 是否真的撤掉了触摸窗。撤窗之后系统还要把新的窗口树交给输入分发器，
     *   调用方（[PlaybackController]）必须等这一刻才起步；没有任何窗可撤时不必等。
     */
    fun setPlaybackActive(active: Boolean): Boolean {
        if (playbackActive == active) return false
        playbackActive = active
        if (active) {
            val removed = touches.isNotEmpty()
            clearTouches(immediate = true)
            touchKey = 0
            return removed
        }
        rebuildTouchWindows()
        return false
    }

    /**
     * 按当前圆点重建触摸窗。
     *
     * 只在「几何真的变了」时重建：仓库与播放状态的每条通知都会走 [refresh]，
     * 二十个圆点若每次都删了重加，就是每次几十次系统窗口事务，白耗电也白闪。
     * 与其它悬浮窗（迷你条）重叠的圆点不建窗——同类型窗口谁后加谁在上，
     * 建了就会把对方的按钮盖死；圆点本身照常绘制，拖动让位给按钮。
     * 播放中一个都不建：注入的点击会先命中这些窗，把脚本自己的点击吞掉。
     *
     * 同一像素上的多个圆点共用一个窗（见 [TouchWindowPolicy.stacks]）：各建一个的话
     * 只有下标大的那个能收到触摸，其余永远拖不动。行为契约——
     * 首次拖这堆点动的是最上层（下标最大）那颗；点一下切换目标；再拖只动选中那颗；
     * 单颗圆点的行为不变。
     */
    private fun rebuildTouchWindows() {
        val view = markers
        val app = appContext
        val wm = windowManager
        // 播放中不建窗，见 [TouchWindowPolicy]：注入点击会先落到圆点窗上被吞掉
        val playing = playbackActive || PlaybackController.state != PlaybackEngine.State.IDLE
        if (view == null || app == null || wm == null ||
            !TouchWindowPolicy.shouldBuild(moveEnabled, playing)
        ) {
            clearTouches()
            touchKey = 0
            return
        }
        // 触摸窗优先用无障碍服务的窗口管理器挂 TYPE_ACCESSIBILITY_OVERLAY：
        // 普通 TYPE_APPLICATION_OVERLAY 在本机把硬件手指触摸判给了 App 自己的 Activity（穿透），
        // 只有无障碍覆盖层能稳定接到手指触摸。无服务时退回普通悬浮窗。
        val a11yService = com.auto.fairy.AutoFairyAccessibilityService.instance
        val touchWm = a11yService?.getSystemService(WindowManager::class.java) ?: wm
        val touchType = if (a11yService != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val touchCtx = a11yService ?: app
        touchWindowManager = touchWm
        val screen = ScreenMetrics.size(app)
        val span = view.touchSpan()
        val total = view.markerCount()
        // 超上限时只建前 N 个，但必须让用户知道，不能静默少一半
        notifyOverflow(app, total)
        val count = TouchWindowPolicy.count(total)
        val blocked = obstacleRects()
        val key = geometryKey(view, screen.x, screen.y, span, count, blocked)
        if (key == touchKey) return

        clearTouches()
        val px = IntArray(count)
        val py = IntArray(count)
        for (index in 0 until count) {
            val entry = view.entryAt(index) ?: continue
            px[index] = (entry.point.x * screen.x).toInt()
            py[index] = (entry.point.y * screen.y).toInt()
        }
        for (stack in TouchWindowPolicy.stacks(px, py)) {
            val first = stack.first()
            val cx = px[first]
            val cy = py[first]
            val left = (cx - span / 2f).toInt()
            val top = (cy - span / 2f).toInt()
            // 整块触摸窗都要让开小窗，不能只看圆点中心：窗是 span×span，
            // 圆心在窗外一点点就仍会盖住小窗按钮——真机实测「点播放没反应」
            // （竖屏圆点 (746,1336) 的窗盖住迷你条播放键的下半部分）。
            if (blocked.any {
                    TouchWindowPolicy.touches(
                        cx, cy, span, it.left, it.top, it.right, it.bottom,
                    )
                }
            ) {
                continue
            }
            val touch = View(touchCtx).apply {
                // 不能是「纯透明」：MIUI/HyperOS 会把像素全透明的悬浮窗当作触摸穿透，
                // 手指事件直接漏给下层（实测坐标层收不到 DOWN）。给 1/255 的近乎不可见底色，
                // 让窗口被当作普通可见窗接收硬件触摸。
                setBackgroundColor(0x01000000)
                contentDescription = app.getString(R.string.coord_drag_hint)
            }
            val lp = WindowManager.LayoutParams(
                span,
                span,
                touchType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = left
                y = top
            }
            touch.tag = lp
            touch.setOnTouchListener(markerDragListener(stack, span))
            runCatching { touchWm.addView(touch, lp) }
                .onSuccess { touches.add(touch) }
        }
        touchKey = key
    }

    /** 需要避让的矩形：注册的其它悬浮窗（迷你条）。 */
    private fun obstacleRects(): List<Rect> {
        val out = ArrayList<Rect>(2)
        // provider 自己就是 null 安全的（[OverlayController.boundsPx]），这里不再包一层
        obstacles.values.forEach { provider -> provider()?.let { out.add(it) } }
        return out
    }

    /** 圆点超过上限时提示一次（同一脚本只提一次）：超出部分不可拖动。 */
    private fun notifyOverflow(context: Context, total: Int) {
        if (!TouchWindowPolicy.overflow(total)) {
            overflowNotifiedId = null
            return
        }
        val id = script?.id ?: return
        if (id == overflowNotifiedId) return
        overflowNotifiedId = id
        showToast(
            context,
            context.getString(R.string.coord_too_many, TouchWindowPolicy.MAX_MARKERS),
        )
    }

    /** 几何指纹：圆点像素位置、命中边长、屏幕尺寸、避让区任一变化都要重摆窗口。 */
    private fun geometryKey(
        view: CoordinateMarkers,
        screenW: Int,
        screenH: Int,
        span: Int,
        count: Int,
        blocked: List<Rect>,
    ): Int {
        var h = 17
        h = 31 * h + screenW
        h = 31 * h + screenH
        h = 31 * h + span
        h = 31 * h + count
        for (index in 0 until count) {
            val entry = view.entryAt(index) ?: continue
            h = 31 * h + (entry.point.x * screenW).toInt()
            h = 31 * h + (entry.point.y * screenH).toInt()
        }
        blocked.forEach { h = 31 * h + it.left + 7 * it.top + 13 * it.right + 3 * it.bottom }
        return h
    }

    private fun clearTouches(immediate: Boolean = false) {
        val wm = touchWindowManager ?: windowManager
        touches.forEach {
            runCatching {
                if (immediate) wm?.removeViewImmediate(it) else wm?.removeView(it)
            }
        }
        touches.clear()
    }

    /**
     * 一堆（同一像素）圆点共用的拖动：窗口跟着手指走，绘制层同步显示预览位置，松手写回脚本。
     * 只有这一个 [span] 见方的窗口吃触摸，满屏绘制层仍是 FLAG_NOT_TOUCHABLE。
     *
     * [stack] 是同一像素上的圆点下标（升序）。点一下不拖 → 换下一颗被拖的目标，
     * 并把它的序号点亮当选中提示；拖动与提交一律用当前选中的那颗。
     */
    private fun markerDragListener(stack: List<Int>, span: Int): View.OnTouchListener {
        var slop = 0f
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        // 默认最上层（下标最大）：与绘制顺序一致，用户看到的就是最先该动的那颗
        var picked = stack.lastOrNull() ?: 0

        return View.OnTouchListener { view, event ->
            val lp = view.tag as? WindowManager.LayoutParams ?: return@OnTouchListener false
            if (slop == 0f) slop = dp(view.context, DRAG_SLOP_DP).toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && abs(dx) + abs(dy) > slop) {
                        dragging = true
                    }
                    if (dragging) {
                        clampMarker(lp, startX + dx.toInt(), startY + dy.toInt(), span)
                        runCatching { windowManager?.updateViewLayout(view, lp) }
                        markers?.setDragPreview(picked, lp.x + span / 2f, lp.y + span / 2f)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        commit(picked, lp.x + span / 2f, lp.y + span / 2f)
                    } else if (stack.size > 1) {
                        picked = TouchWindowPolicy.nextPick(stack, picked)
                    }
                    markers?.clearDragPreview()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    // 拖动中被系统打断（无障碍重连、输入设备切换、另一次注入）时，文件与绘制层
                    // 都没动，窗口却停在手指离开处：与写盘失败同一后果——圆点画在原位，热点却
                    // 留在旁边且毫无提示。按圆点实际位置重摆一次；同理放在下一条消息里做，
                    // 不在自己的 onTouch 回调里摘自己的窗口（打断后补来的 MOVE 会落到已摘掉的窗上）。
                    if (dragging) {
                        touchKey = 0
                        handler.post { rebuildTouchWindows() }
                    }
                    markers?.clearDragPreview()
                    true
                }

                else -> false
            }
        }
    }

    private fun clampMarker(lp: WindowManager.LayoutParams, x: Int, y: Int, span: Int) {
        val app = appContext ?: return
        val screen = ScreenMetrics.size(app)
        lp.x = x.coerceIn(0, (screen.x - span).coerceAtLeast(0))
        lp.y = y.coerceIn(0, (screen.y - span).coerceAtLeast(0))
    }

    /** 松手：像素位置换回归一化坐标，按路径写回脚本。 */
    private fun commit(index: Int, px: Float, py: Float) {
        val app = appContext ?: return
        val current = script ?: return
        val entry = markers?.entryAt(index) ?: return
        val screen = ScreenMetrics.size(app)
        val np = Point(
            (px / screen.x).coerceIn(0f, 1f),
            (py / screen.y).coerceIn(0f, 1f),
        )
        val updated = current.replaceAt(entry.path, np)
        script = updated
        // 写盘会触发仓库通知 → 重建触摸窗（含摘掉正在处理本次触摸的那个窗口）。
        // 放在下一条消息里做：不在自己的 onTouch 回调里摘自己的窗口。
        // 走到这里说明 added 且 script 非空，repository 必然已设（[show] 一并赋三件）。
        val repo = repository ?: return
        handler.post {
            // 写盘失败不能崩进程：主线程未捕获异常会连正在跑的回放一起丢（与其它写盘点同一契约）
            val saved = repo.saveOrNull(updated) != null
            if (!saved) {
                // 失败时没有仓库通知，绘制层不会重绑，圆点仍画在旧位（与文件一致），
                // 但触摸窗留在手指松开处——那颗点从此点不动，空位上却有个看不见的热点。
                // 按旧坐标重建触摸窗，把热点收回圆点下面。
                script = current
                touchKey = 0
                rebuildTouchWindows()
            }
            toast(if (saved) R.string.toast_coord_saved else R.string.toast_save_failed)
        }
    }

    // ── 小工具 ──────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toast(resId: Int) = showToast(appContext, resId)

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

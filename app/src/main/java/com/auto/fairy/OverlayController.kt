package com.auto.fairy

import android.content.ComponentCallbacks
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.auto.fairy.core.inject.Health
import com.auto.fairy.core.runtime.PlaybackEngine
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import com.auto.fairy.databinding.OverlayMiniBarBinding
import com.auto.fairy.overlay.CoordinateMarkersController
import kotlin.math.abs

/**
 * 迷你悬浮窗控制器（进程级单例）。
 *
 * 形态：单层常驻条，无圆形菜单球、无工具条——深色半透明底 + 白图标，
 * 叠在浅色或深色画面上都看得清（裸深色图标在游戏画面上几乎不可见）。
 * 布局：开始/暂停切换 · 脚本名前 6 字 · 停止 · 添加(加号) · 关闭；脚本名区域兼作拖动手柄。
 *
 * 只负责「显示 + 把用户操作翻译成 Command」，不直接碰引擎内部；
 * 播放状态/循环次数全部从 [PlaybackController] 读取；坐标点由
 * [com.auto.fairy.overlay.CoordinateMarkersController] 自己画（可拖时另挂触摸窗），
 * 两条窗口互不隶属——本条只收放自己。
 */
object OverlayController {

    private const val TAG = "OverlayController"
    private const val PREFS = "overlay_window"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val DRAG_SLOP_DP = 8f

    /** 贴边留白：图标压在屏幕边缘时会被系统裁掉。 */
    private const val SCREEN_MARGIN_DP = 4f

    /** 脚本名最多显示的字符数（按码点算，更长直接截断，不加省略号）。 */
    private const val NAME_LIMIT = 6

    /** 连续空白：名字里的换行/制表符都要压平，见 [oneLine]。 */
    private val WHITESPACE = Regex("\\s+")

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var binding: OverlayMiniBarBinding? = null
    private var params: WindowManager.LayoutParams? = null
    private var preferences: SharedPreferences? = null
    private var added = false

    /** 脚本名缓存：refresh 被每次状态回调与每巡一次的进度回调反复调用，不能每次读文件。 */
    private var cachedNameId: String? = null
    private var cachedName: String? = null

    /**
     * 仓库变更监听：改名 / 删除 / 新增都要立刻让小窗名字跟上。
     *
     * 只清名字缓存并 refresh，不读盘（读盘仍在缓存未命中时按需发生），
     * 因此编辑器每保存一次最多多一次 IO，不会形成常驻轮询。
     */
    private val repositoryListener: () -> Unit = {
        cachedNameId = null
        cachedName = null
        if (added) refresh()
    }

    private val playbackListener = object : PlaybackController.Listener {
        override fun onPlaybackState(state: PlaybackEngine.State, scriptId: String?) {
            // 图标只由 [PlaybackController.state] 决定：停止请求一发出就是 IDLE，
            // 不会再出现「已点停止但仍是暂停双竖线」。出错提示走 Toast，不留在图标上。
            refresh()
        }

        // onProgress 不重写：刷新读的只有 state / health / 缓存名字，没有一个随巡数变

        override fun onError(message: String) {
            // 悬浮窗是主要操作面，出错必须有文字提示，不能只把图标变色
            showToast(appContext, message)
            refresh()
        }

        override fun onNotice(message: String) {
            showToast(appContext, message)
        }
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 唯一入口：只有「激活」会调用本方法。
     *
     * 曾有的 [attach]/[ensureShown] 自动挂出路径已全部删除——App 启动、无障碍连接、
     * 录制或取坐标结束都不再把小窗拉出来，避免「没点激活却有小窗」。
     */
    fun show(context: Context): Boolean {
        val app = context.applicationContext
        // 权限被撤时系统会直接摘掉我们的窗口，本类的 added 却还停在「已显示」：
        // 实测撤权限后点激活只 refresh() 了一条不存在的窗口，界面照样提示「已激活」。
        // 所以先判权限，并顺手清掉残留状态，再给出原因。
        if (!Settings.canDrawOverlays(app)) {
            // hide() 里已停回放并收坐标层，这里只补一句原因提示
            hide()
            showToast(app, R.string.toast_permission_overlay)
            return false
        }
        if (added) {
            // 已在显示：调用方多半是「激活了另一条脚本」，名字必须立刻跟上，
            // 否则小窗写着上一条、点开始却播新的一条。
            refresh()
            // 会话已在却又点了一次激活 = 用户要再看这次会话的坐标：层若上次挂失败、
            // 或被系统摘掉，只有 [restore] 会重挂（换脚本由仓库通知重绑，不走这里）。
            // 开关关着时 restore 自己会返回，不会替用户打开。
            CoordinateMarkersController.restore(app)
            return true
        }
        appContext = app
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wm = app.getSystemService(WindowManager::class.java)
        windowManager = wm
        // 应用 context 不带 manifest 里的主题，浮层必须显式套 AppCompat 主题，
        // 否则任何 ?attr 引用都会在 inflate 时抛 UnsupportedOperationException
        val themed = android.view.ContextThemeWrapper(app, R.style.Theme_AutoFairy)
        val view = OverlayMiniBarBinding.inflate(android.view.LayoutInflater.from(themed))

        val screen = ScreenMetrics.size(app)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences?.getInt(KEY_X, screen.x / 2) ?: (screen.x / 2)
            y = preferences?.getInt(KEY_Y, screen.y / 3) ?: (screen.y / 3)
        }

        binding = view
        params = layoutParams

        // detach 只覆盖「窗口真的被移出窗口树」（本类 hide、系统回收窗口）。
        // 撤悬浮窗权限**不走这里**：系统只把浮层 setHidden，窗口仍留在 WMS 里
        // （第十七轮实测 added 仍为 true、三个窗口仍列出但一个都不画在屏上），
        // 那条路径由 PlaybackController.watchOverlayPermission 的 AppOps 监听收尾。
        view.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                if (v !== binding?.root) return
                // 窗口消失与点 ✕ 同一条不变式：操作面没了 = 这次操作结束。
                // 只 hide 不停回放，等于把点击器藏起来继续点，用户已经无处可停。
                PlaybackController.stop()
                CoordinateMarkersController.hide()
                hide()
            }
        })

        // 四个动作一律按下就执行，不等抬手：播放中注入的手势会作废在途的真触摸流
        // （DOWN 之后 34~149ms 收到 CANCEL，永远等不到 UP，见 [A11yBackend] 类注释），
        // 等 UP 的点击在 160ms 一拍的脚本上 8/8 全失效。代价是「按下再滑走取消」不再成立：
        // 停止、暂停是幂等动作，关闭后点「激活」即可回来，都无需撤销窗口。
        //
        // 加号也按下即开（第二十六轮实测：真触摸按加号 0/6 打开，注入通道 3/3 打开）。
        // 误触代价实测只有「暂停 + 弹层」，追加步骤要点第二下才发生，两下就能退回；
        // 而同一排的停止键代价更大却早就是按下即生效——留一个「按了没反应」的键才是缺陷。
        onPress(view.btnOverlayToggle) { onToggleClicked() }
        onPress(view.btnOverlayStop) { PlaybackController.stop() }
        // 关闭 = 结束这次操作：停回放，小窗与坐标层一起消失
        // （见 [closeScriptSession]；不这样等于把点击器藏起来继续跑）
        onPress(view.btnOverlayClose) {
            closeScriptSession()
            view.root.context.let { ctx ->
                showToast(ctx, R.string.toast_overlay_closed)
            }
        }
        onPress(view.btnOverlayAdd) { OverlayQuickAdd.open(view.root.context) }
        // 问号：切换「显示坐标标记」，与设置页同口径（CoordinateMarkersController.setOn）。
        // 按下即切：它和加号/关闭一样是幂等开关，无需等抬手。
        onPress(view.btnOverlayHelp) {
            val ctx = view.root.context
            val next = !CoordinateMarkersController.isOn(ctx)
            CoordinateMarkersController.setOn(ctx, next)
            syncHelpButton(ctx)
        }
        // 脚本名区域空间不浪费：同时充当拖动手柄（按下拖动移动，轻点无动作）
        view.textOverlayName.setOnTouchListener(dragListener())

        try {
            wm.addView(view.root, layoutParams)
            added = true
        } catch (t: Throwable) {
            // 加不上要留痕也要说出来：静默失败会让「点激活没反应」无从查起
            Log.w(TAG, "迷你条 addView 失败", t)
            showToast(app, R.string.toast_overlay_add_failed)
            binding = null
            params = null
            return false
        }

        // 坐标层跟着本次会话走，挂在这里而不是 addView 之前：
        // 闸门读的就是 [isShowing]（= 已挂上的 [added]），先恢复只会被挡掉，
        // 用户点了激活却看不到坐标点。挂不挂仍由设置页开关决定（restore 内查，不写）。
        CoordinateMarkersController.restore(app)

        PlaybackController.addListener(playbackListener)
        AutoFairyApp.of(app).repository.addListener(repositoryListener)
        view.root.post {
            clampToScreen()
            // 条的位置/尺寸要等布局完成才准确，之后让坐标层重摆触摸窗
            CoordinateMarkersController.onObstaclesChanged()
        }
        // 尺寸/旋转变化后要重新夹住：物理像素坐标 + WRAP_CONTENT，转屏后原坐标
        // 可能整条落在屏外（看不见也点不到，■ 与 ✕ 一起消失）。
        app.registerComponentCallbacks(configCallbacks)
        refresh()
        return true
    }

    fun hide() {
        val view = binding
        val wasAdded = added
        added = false
        OverlayQuickAdd.close()
        PlaybackController.removeListener(playbackListener)
        appContext?.let { AutoFairyApp.of(it).repository.removeListener(repositoryListener) }
        appContext?.unregisterComponentCallbacks(configCallbacks)
        // 操作面消失 = 本次操作结束，必须自己停：detach 由 MSG_DIE 延后投递
        // （实测 removeView 返回后 30ms 才到），那时 binding 已清空、守卫恒假，
        // 靠它停回放会让「条没了、手机还在自己点」。坐标层要一并收，否则红点留在屏上。
        PlaybackController.stop()
        CoordinateMarkersController.hide()
        if (view != null && wasAdded) {
            runCatching { windowManager?.removeView(view.root) }
        }
        binding = null
        params = null
        cachedNameId = null
        cachedName = null
        // 条没了，原先被它压住的圆点可以重新建触摸窗
        CoordinateMarkersController.onObstaclesChanged()
    }

    /**
     * 是否还有一次进行中的会话——激活会话的唯一真源。
     *
     * 记账就用 [added]，不另立状态：激活成功置位，[hide]（✕、窗口被摘、权限被撤、
     * 录制独占）清位。面板/编辑器只把条设成 GONE（[hidePanel]），会话仍在，
     * 所以这里不读可见性。
     * 坐标层据此决定能不能挂出：没有条 = 这次操作已结束，屏幕上不该还留着一片红点
     * （实测：主页只有列表和红点、根本没有迷你条，用户判定这是 bug）。
     */
    fun isShowing(): Boolean = added

    /**
     * 尺寸/旋转变化后重新夹住本条。
     *
     * 位置是物理像素 + WRAP_CONTENT，转屏（或 wm size 改变）后原坐标可能整条落在屏外：
     * 看不见也点不到，■ 与 ✕ 一起消失。窗口自己不会跟着夹，只能听配置变化。
     */
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = clampToScreen()
        override fun onLowMemory() = Unit
    }

    /**
     * 用户点小窗的「×」：结束这次操作——先停回放，再收小窗与坐标层。
     *
     * 只收窗口不停回放会留下一个「看不见、停不了」的后台点击器：窗口没了，■ 也没了。
     * 坐标层是「这个脚本的可视化」，操作面都收了还挂着一片红点，用户会以为还在运行，
     * 所以这里连层一起收；但**不动用户的显示开关**——开关是设置页的事，
     * 下次激活按开关恢复（见 [show]）。
     */
    private fun closeScriptSession() {
        PlaybackController.stop()
        CoordinateMarkersController.hide()
        hide()
    }

    /**
     * 本条的屏幕像素矩形（未显示时为 null）。
     *
     * 坐标层据此避让：同类型悬浮窗谁后 add 谁在上，圆点触摸窗若压在按钮上，
     * 播放/停止就点不动了。
     */
    fun boundsPx(): Rect? {
        if (!added) return null
        val view = binding?.root ?: return null
        val layout = params ?: return null
        if (view.visibility != View.VISIBLE) return null
        if (view.width <= 0 || view.height <= 0) return null
        return Rect(layout.x, layout.y, layout.x + view.width, layout.y + view.height)
    }

    // ── 面板收起 / 隐藏（供浮层互斥调用） ────────────────────

    /**
     * 面板整体隐藏（图标也不显示），用于全屏吃触摸的取坐标浮层；
     * [showBall] 可恢复。
     */
    fun hidePanel() {
        binding?.root?.visibility = View.GONE
        // 条不可见了，它压住的圆点可以重新建触摸窗
        CoordinateMarkersController.onObstaclesChanged()
    }

    /** 恢复常驻条。 */
    fun showBall() {
        binding?.root?.visibility = View.VISIBLE
        binding?.root?.post { clampToScreen() }
        CoordinateMarkersController.onObstaclesChanged()
    }

    // ── 交互 ────────────────────────────────────────────────

    /** 切换键：运行中暂停、已暂停继续、其余启动当前脚本。 */
    private fun onToggleClicked() {
        when (PlaybackController.state) {
            PlaybackEngine.State.RUNNING -> {
                PlaybackController.pause()
                return
            }

            PlaybackEngine.State.PAUSED -> {
                PlaybackController.resume()
                return
            }

            PlaybackEngine.State.IDLE -> Unit
        }
        if (!PlaybackController.isBackendReady()) {
            toast(R.string.toast_a11y_needed)
            return
        }
        // 失败原因由 playCurrent/play 内部 notifyError 说清（编译失败、屏幕失配、
        // 目标读不出…）；这里再补一句「请先新建」会把那句话挤掉，只剩误导。
        // 只有「确实一条脚本都没有」时它才是对的，而那种情况同样已经 notify 过。
        PlaybackController.playCurrent()
    }

    /**
     * 播放中注入的点击不是用户按的：与 [MainActivity.dispatchTouchEvent] 同一判据
     * （`injecting && deviceId < 0`）。不加这条，脚本点到条上会把自己暂停/停止/关窗
     * ——与「圆点窗吞掉脚本自己的点击」（见 [CoordinateMarkersController]）同源。
     * 真触摸 deviceId 恒 ≥0（实测=3），不会被误伤。
     */
    private fun MotionEvent.isInjectedDuringPlayback(): Boolean =
        PlaybackController.injecting && deviceId < 0

    /**
     * 按下（ACTION_DOWN）就执行一次，不等抬手。
     *
     * 播放中注入的手势会作废在途的真触摸流：真触摸按下迷你条后 34~149ms 就被 CANCEL，
     * 永远等不到 UP，`setOnClickListener` 因此一次也不成立（同一坐标走 `input tap`
     * 注入则次次生效）。停止/暂停/关闭都是按下即算数的动作，所以挂在 DOWN 上。
     */
    private fun onPress(view: View, action: () -> Unit) {
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                !event.isInjectedDuringPlayback()
            ) {
                action()
            }
            true
        }
    }

    // ── 拖动 ────────────────────────────────────────────────

    private fun dragListener(): View.OnTouchListener {
        var slop = 0f
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        return View.OnTouchListener { view, event ->
            val layout = params ?: return@OnTouchListener false
            // 注入的手势整段忽略：不记起点、不改 dragging，窗口因此一动不动。
            // 只吞 DOWN 会让上一次留下的 dragging 与旧起点继续生效，第一个 MOVE 就把条弹飞
            if (event.isInjectedDuringPlayback()) return@OnTouchListener true
            if (slop == 0f) {
                slop = DRAG_SLOP_DP * view.resources.displayMetrics.density
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // rawX 与 LayoutParams.x 同为物理像素，可直接相加
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && abs(dx) + abs(dy) > slop) dragging = true
                    if (dragging) {
                        layout.x = startX + dx.toInt()
                        layout.y = startY + dy.toInt()
                        clampToScreen()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdge()
                        savePosition()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge()
                    true
                }

                else -> false
            }
        }
    }

    private fun clampToScreen() {
        val view = binding?.root ?: return
        val layout = params ?: return
        val screen = ScreenMetrics.size(view.context)
        // 全部用物理像素：LayoutParams.x/y、rawX、ScreenMetrics 同一个坐标系，
        // 掺进 dp 会让 maxX 偏大，窗口右边被挤到屏幕外（关闭键就点不到了）
        val density = view.resources.displayMetrics.density
        val margin = (SCREEN_MARGIN_DP * density).toInt()
        val width = if (view.width > 0) view.width.toFloat() else 200f * density
        val height = if (view.height > 0) view.height.toFloat() else 48f * density
        val maxX = (screen.x - width - margin).coerceAtLeast(margin.toFloat())
        val maxY = (screen.y - height - margin).coerceAtLeast(margin.toFloat())
        layout.x = layout.x.coerceIn(margin, maxX.toInt())
        layout.y = layout.y.coerceIn(margin, maxY.toInt())
        runCatching { windowManager?.updateViewLayout(view, layout) }
    }

    private fun snapToEdge() {
        val view = binding?.root ?: return
        val layout = params ?: return
        // 用户关掉贴边时只做边界夹取，不强制吸附
        if (!AppSettings.isSnapEdgeEnabled(view.context)) {
            clampToScreen()
            return
        }
        val screen = ScreenMetrics.size(view.context)
        val density = view.resources.displayMetrics.density
        val margin = (SCREEN_MARGIN_DP * density).toInt()
        val width = if (view.width > 0) view.width.toFloat() else 200f * density
        layout.x = if (layout.x + width / 2 < screen.x / 2f) {
            margin
        } else {
            (screen.x - width - margin).toInt()
        }
        clampToScreen()
    }

    private fun savePosition() {
        val layout = params ?: return
        preferences?.edit()?.putInt(KEY_X, layout.x)?.putInt(KEY_Y, layout.y)?.apply()
        // 位置变了：被条压住的圆点让出触摸窗，让按钮恢复可点
        CoordinateMarkersController.onObstaclesChanged()
    }

    // ── 状态刷新 ────────────────────────────────────────────

    private fun refresh() {
        val view = binding ?: return
        val context = view.root.context
        // 唯一状态源：PlaybackController 已把「停止请求已发出、引擎还在收尾」折算成 IDLE，
        // 因此这里不可能出现「已停止却仍显示暂停双竖线」。
        val state = PlaybackController.state
        val health = PlaybackController.health()
        val running = state == PlaybackEngine.State.RUNNING
        val dead = health == Health.DEAD

        // 切换键图标：运行中双竖线（暂停），其余侧向三角（开始）
        if (running) {
            view.btnOverlayToggle.setImageResource(R.drawable.ic_pause_bars)
            view.btnOverlayToggle.contentDescription = context.getString(R.string.menu_pause)
        } else {
            view.btnOverlayToggle.setImageResource(R.drawable.ic_play_triangle)
            view.btnOverlayToggle.contentDescription = context.getString(R.string.menu_play)
        }

        // DEAD 时后端没了，开始没意义：置灰禁用，保留停止以防卡死
        view.btnOverlayToggle.isEnabled = !dead
        view.btnOverlayToggle.alpha = if (dead) 0.4f else 1f

        // 脚本名只取前 6 字，无脚本时用占位文案
        val name = currentScriptName(context)
        val label = if (name == null) {
            context.getString(R.string.mini_no_script)
        } else {
            oneLine(name, NAME_LIMIT)
        }
        if (view.textOverlayName.text != label) {
            view.textOverlayName.text = label
            // 条是 WRAP_CONTENT，名字长短会改变宽度，避让矩形跟着变。改字的这一刻布局还没跑，
            // 坐标层量到的是旧宽度，必须等布局生效再让它重摆一次，否则变宽的那一截会压住
            // 圆点触摸窗（同 N1：按钮点不动，只是换成名字变化触发）。
            view.root.post { CoordinateMarkersController.onObstaclesChanged() }
        }

        // 问号反映坐标标记开关状态，与设置页 / prefs 保持同步（条子重显时也要对齐）
        syncHelpButton(context)
    }

    /**
     * 问号按钮的选中态：坐标标记开 → 主色 [@color/overlay_text]；关 → 次级色
     * [@color/overlay_text_secondary] 并降透明度，与条上其它键区分、不新造颜色。
     */
    private fun syncHelpButton(context: Context) {
        val view = binding ?: return
        val on = CoordinateMarkersController.isOn(context)
        view.btnOverlayHelp.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (on) R.color.overlay_text else R.color.overlay_text_secondary,
            ),
        )
        view.btnOverlayHelp.alpha = if (on) 1f else 0.55f
    }

    /**
     * 名字压成一行（连续空白算一个空格）并按**码点**截断。
     *
     * 迷你条按 [NAME_LIMIT] 截，浮层导出按 [OverlayScriptList.FILE_NAME_LIMIT] 截。
     * 两个坑都在原始 `take(6)` 里：命中换行会把迷你条撑成两行（压在底下的按钮点不到），
     * 命中 emoji 的代理对会劈成半个字符（显示成方块）。名称可以来自导入的 JSON，
     * 编辑器限制不了这两类字符；导出侧同一份名字还要当文件名，换行会进文件名、
     * 长中文名会撞 255 字节上限。
     */
    internal fun oneLine(name: String, limit: Int): String {
        val flat = name.replace(WHITESPACE, " ").trim()
        if (flat.codePointCount(0, flat.length) <= limit) return flat
        return flat.substring(0, flat.offsetByCodePoints(0, limit))
    }

    /**
     * 小窗显示的脚本名：与 [PlaybackController.playTargetId] 完全同源——写的就是
     * 「点开始会播的那条」，玩家不会看到名字与实际脚本不一致。
     * 没有目标时走 [ScriptRepository.fallbackId]，与 [PlaybackController.playCurrent]
     * 的回落同一条链（上次播放 → 列表首项）；用「最近修改」会在目标被删除时显示一条、
     * 实际播另一条。
     */
    private fun currentScriptName(context: Context): String? {
        val repo = AutoFairyApp.of(context).repository
        val id = PlaybackController.resolvedTargetId()
            ?: return null
        if (id == cachedNameId) return cachedName
        // 目标文件在但读不出：名字拿不到，但要明说「脚本损坏」——显示「无脚本」
        // 会让用户以为脚本没了，而按开始又一直报读取失败，两边说法对不上
        val name = repo.load(id)?.name ?: context.getString(R.string.mini_script_broken)
        cachedNameId = id
        cachedName = name
        return name
    }

    private fun toast(resId: Int) = showToast(appContext, resId)

}

package com.auto.fairy

import android.app.AppOpsManager
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Window
import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.compiler.Timeline
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.inject.Health
import com.auto.fairy.core.inject.InputBackend
import com.auto.fairy.A11yBackend.Companion.ERR_NOT_CONNECTED
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import com.auto.fairy.core.runtime.Command
import com.auto.fairy.core.runtime.PlaybackEngine
import com.auto.fairy.core.runtime.TimelineCursor
import com.auto.fairy.core.runtime.Slice
import com.auto.fairy.overlay.CoordinateMarkersController
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回放控制的唯一入口（进程级单例）。
 *
 * 铁律：Activity / Fragment / 悬浮窗都只能通过本类操作，最终统一变成
 * [Command] 交给 [PlaybackEngine]；任何地方都不允许直接调用引擎内部方法。
 * 引擎固定为单实例 + 单 runToken，悬浮窗重复点击不会叠加出第二条回放。
 */
object PlaybackController {

    interface Listener {
        /** 播放状态变化（IDLE / RUNNING / PAUSED）。 */
        fun onPlaybackState(state: PlaybackEngine.State, scriptId: String?) {}

        /** 已完成的整段循环次数（从 0 开始）。 */
        fun onProgress(cycle: Long) {}

        /** 播放异常/启动失败。 */
        fun onError(message: String) {}

        /** 非错误提示（如重复点播放的处理结果），界面用 Toast 呈现。 */
        fun onNotice(message: String) {}
    }

    private lateinit var appContext: Context
    private var repository: ScriptRepository? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val backend = DelegatingBackend()
    private var engine: PlaybackEngine? = null
    private var currentScriptId: String? = null
    private var loopLimit: Int = 0

    /** [pauseForModal] 真的停过一次、还没提示过：关浮层时据此弹「已暂停」。 */
    private var pausedForModal = false

    /**
     * 用户已点停止、引擎线程尚未真正退出的过渡态。
     *
     * 引擎的 Stop 是投递给回放线程的命令，真正置 IDLE 要等当前片收尾（最长数百毫秒）；
     * 界面若直接读引擎状态，会出现「已点停止但按钮仍是暂停双竖线」。
     * 对外一律先按 IDLE 呈现，引擎确认退出后自动清掉这个标记。
     */
    @Volatile
    private var stopRequested = false

    /**
     * 「这一次播放」的代号。
     *
     * 重启回放要等旧线程退出（[startTimeline] 的 awaitIdle），等待期间可能又有新的
     * 播放请求或一次停止进来。异步的 runStart 只认自己那一代：代号变了就不启动，
     * 否则「点了停止它却自己跑起来」「连点两次播放报启动失败」都会出现。
     */
    private val playToken = AtomicInteger(0)

    @Volatile
    private var lastErrorMessage: String? = null

    fun lastError(): String? = lastErrorMessage

    fun init(context: Context) {
        appContext = context.applicationContext
        repository = AutoFairyApp.of(appContext).repository
        // 删除脚本后清游标：小窗名字不能指着已删除的脚本（显示与目标一致）
        repository?.addListener(::onRepositoryChanged)
        watchOverlayPermission()
    }

    /**
     * 悬浮窗权限被撤时收尾。
     *
     * 系统撤权限只把我们的浮层**隐藏**（窗口仍留在 WMS 里、没有 detach），
     * 所以 `OverlayController` 的摘窗回调不会来：用户看到的是「小窗没了、点击还在跑」
     * 而没有任何可停之处。实测（MuMu/API32）：撤权限后 1 分钟回放线程仍在，
     * 三个浮层窗口仍列在 WMS 里但一个都不画在屏上。
     * AppOps 的模式变化是这里唯一能拿到的信号，收到就按「小窗被摘掉」同一套动作收尾。
     */
    private fun watchOverlayPermission() {
        val ops = appContext.getSystemService(AppOpsManager::class.java) ?: return
        ops.startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            appContext.packageName,
            object : AppOpsManager.OnOpChangedListener {
                override fun onOpChanged(op: String?, packageName: String?) {
                    if (Settings.canDrawOverlays(appContext)) return
                    handler.post {
                        // hide() 内已停回放并收坐标层，这里只补脚本列表浮层
                        OverlayController.hide()
                        // 脚本列表同是满屏浮层：撤权限同样只被隐藏、不 detach，
                        // 留在屏上的是一个看不见却照吃触摸的全屏窗口（实测：权限恢复后
                        // 第一次点「脚本」被它吃掉，第二次才打开）。小窗的 hide 只收加号层。
                        OverlayScriptList.close()
                    }
                }
            },
        )
    }

    /**
     * 仓库变更时核对「本次运行的脚本是否还在」：不在就清游标。
     *
     * 只看文件在不在：原实现用 `load()` 判存在，为了一个布尔值把整份 JSON
     * 读出来解析一遍（保存/删除都会走到这里，主线程）；而文件坏掉时它返回
     * null 会把播放目标也清掉——「脚本坏了」不该被当成「脚本被删了」。
     */
    private fun onRepositoryChanged() {
        val id = currentScriptId ?: return
        if (repository?.exists(id) == true) return
        if (state != PlaybackEngine.State.IDLE) return
        currentScriptId = null
        notifyState()
    }

    /** 无障碍服务连接成功后由服务调用，安装真正的注入后端。 */
    fun attachBackend(input: InputBackend) {
        backend.delegate = input
        notifyState()
    }

    /** 服务被解绑/销毁：移除后端，正在跑的回放会因 prepare/execute 失败而结束。 */
    fun detachBackend(input: InputBackend) {
        // 重连时可能出现「新实例已连接、旧实例才回调 onUnbind」，按身份判断避免误清
        if (backend.delegate !== input) return
        backend.delegate = null
        // 回放会随 prepare/execute 失败而结束，但用户只看到「突然停了」：
        // 诊断页有记录，主界面却没有文字，必须补一句可见的交代
        if (state != PlaybackEngine.State.IDLE) {
            showToast(appContext, R.string.toast_playback_stopped_no_backend)
        }
        notifyState()
    }

    fun isBackendReady(): Boolean = backend.delegate != null

    fun health(): Health = backend.delegate?.health() ?: Health.DEAD

    /** 当前后端能力；未连接时返回无障碍通道的标称值，诊断页照实标注未连接。 */
    fun caps(): Caps = backend.caps

    val state: PlaybackEngine.State
        get() {
            // 停止指令已发出但引擎还没退出：界面按 IDLE 呈现，见 [stopRequested]
            if (stopRequested) return PlaybackEngine.State.IDLE
            return engine?.takeIf { it.isRunning }?.playbackState ?: PlaybackEngine.State.IDLE
        }

    /**
     * 是否正在跑（RUNNING）。坐标层画箭头动画时据此决定是否续帧：
     * 暂停/停止时箭头必须立刻消失，因此续帧只在 RUNNING 下成立。
     */
    fun isPlaying(): Boolean = state == PlaybackEngine.State.RUNNING

    /** 正在播放（或刚播放完）的脚本；未运行过时为 null。删除确认等「是否在跑这条」用它。 */
    val playingScriptId: String? get() = currentScriptId

    /**
     * 播放目标：运行/暂停时是正在跑的脚本，空闲时是「下次点开始会播的脚本」。
     *
     * 全应用只能有这一处口径：小窗名字、列表高亮、[playCurrent] 都读它，
     * 否则会出现「激活 A 之后小窗仍写着 B」「坐标层画着 B 却在跑 A」这类不一致。
     * 判定本身在 [resolvePlaybackTarget] 里，与「回落列表首项」共用同一条链。
     */
    val playTargetId: String?
        get() = resolvePlaybackTarget(
            playing = state != PlaybackEngine.State.IDLE,
            currentId = currentScriptId,
            lastPlayedId = repository?.lastPlayedId,
            firstListedId = null,
        )

    /**
     * 播放目标的最终解析：没有任何记录（首次运行 / 目标被删）时回落到列表首项。
     *
     * 回落必须惰性求值：[ScriptRepository.fallbackId] 会 stat，未命中还要扫全目录，
     * 索引有变时写盘（fsync）；本方法在每条状态通知、每巡、每次仓库变更都被调用。
     * 小窗名字、坐标层读这一处（它们只做显示，回落到「按开始会跑的那条」才对得上）。
     * **写操作不读它**：加号追加走 [playTargetId]，没有显式目标就不追加——见
     * [OverlayQuickAdd.currentScript]。
     */
    fun resolvedTargetId(): String? = playTargetId ?: repository?.fallbackId()

    /**
     * 运行中：此刻屏幕上的触摸可能来自**我们自己注入的点击**。
     *
     * 注入点击与真实触摸在**事件内容**上是可分辨的（实测：无障碍 `dispatchGesture`、
     * `input tap`、`input motionevent` 都是 `deviceId=-1`，内核真触摸是 3）。但这里仍按
     * 「引擎在跑」判断，理由有二：
     * 1. 失败方向：判据失效（某些 ROM 给注入事件正 deviceId）就等于闸门静默全开，
     *    而 `state` 是我们自己的旗标，宁可多吞也不会漏；
     * 2. 闸门要做的第二件事是「重新暂停」（覆盖音量键恢复），这只能靠状态。
     *
     * 因此任何「点到浮层外部就关闭」的浮层都必须问一句：这一下真是用户点的吗？
     * 实测（第十八轮）：2 步 460ms 的脚本会让播放中打开的设置卡片在半秒内自己关掉，
     * 关掉后点击落到主界面的脚本列表上。遮罩的处置因此改成「运行中不关」，
     * 只认各自的关闭按钮与返回键。
     *
     * **不含暂停**：暂停后引擎停在片边界、不再派发手势，屏幕上的触摸不可能是注入的，
     * 此时仍按「用户点的」处理（含暂停会让暂停后连遮罩都点不掉）。
     *
     * 唯一的例外是 [MainActivity]：那里不暂停、并与 `deviceId < 0` 合用同一判据，
     * 因为主页正是「按了开始还没切走」的那一屏，跟着暂停会打断正常播放
     * （理由与实测见 [MainActivity.dispatchTouchEvent]）。
     */
    val injecting: Boolean get() = state == PlaybackEngine.State.RUNNING

    /**
     * 用户打开模态浮层（设置/诊断/保活面板、编辑器、脚本列表、加号层、弹层菜单、对话框）
     * 时先暂停回放。所有浮层入口各调一次，一处暂停管住全部控件。
     *
     * 为什么不继续用 [injecting] 逐个拦：那只挡「点到浮层外面」，挡不住「点在开关上」。
     * 实测（4 步脚本 640ms 一轮，播放中打开设置页 7 秒）：`snap_edge_enabled` 与
     * `volume_key_enabled` 被点成 false，用户自己关掉的 `coord_visible` 又被点回 true。
     * 开关、色块、输入框、删除确认全都有点击监听，逐个屏蔽既漏（十八轮同类漏 4 次）
     * 又不跟着新增控件走。
     *
     * 只暂停不停止：关掉浮层后按开始键从原进度继续，跑过的轮次不重来。
     * 关掉浮层也**不自动恢复**——面板消失的瞬间恢复，注入点击正好打回它原来的位置。
     * 既然不自动恢复，就要有一句可见的交代：[noticeStillPausedAfterModal]。
     */
    fun pauseForModal() {
        if (state != PlaybackEngine.State.RUNNING) return
        pausedForModal = true
        pause()
    }

    /**
     * 关掉模态浮层时提示「还在暂停」。
     *
     * 谁暂停谁提示：只有 [pauseForModal] 真的停过一次才弹，不需要配对计数。
     * 旗标只有面板与编辑器在关闭时消费；其余浮层（脚本列表、菜单、命名框）只置位，
     * 要等下一次面板/编辑器关闭时才提示——那时回放确实仍停着，话不算错。
     * [resume]、[stop] 与 [play] 会清旗标：回放重新跑起来之后不该再冒出一句「还停着」（实测：
     * 菜单暂停 → 恢复 → 手动暂停 → 开面板再关，修复前会误弹这句）。
     * 提示后立即清旗标：同一个浮层只提示一次。
     */
    fun noticeStillPausedAfterModal(context: Context) {
        if (!pausedForModal) return
        pausedForModal = false
        if (state != PlaybackEngine.State.PAUSED) return
        showToast(context, R.string.toast_paused_after_modal)
    }

    val loopTimes: Int get() = loopLimit

    fun cursorCycle(): Long = engine?.cursor()?.cycle ?: 0L

    fun cursor(): TimelineCursor = engine?.cursor() ?: TimelineCursor(0L, 0)

    // ── 步骤进度（悬浮窗「第 N 步」用） ──────────────────────
    // 语义：按时间轴推算「当前正在执行的步骤」。为什么不用游标：无障碍后端一次
    // 只派发一次按压，短脚本整段仍只占一个切片，切片下标根本不变化。这里用与
    // 编译器一致的展开顺序累加时长，再按已经过去的时间定位步骤；暂停时用冻结
    // 时刻计算，读数不会漂移。
    // 冻结时刻取「按下暂停」那一刻，引擎真正停在片边界，因此读到的是按下瞬间的步，
    // 最多比引擎实际停下的位置早一片（实测等下一片时 20–40ms，片在跑时要等片尾）。
    private var stepEnds: LongArray = LongArray(0)
    private var stepOrdinals: IntArray = IntArray(0)
    private var cyclePeriodMs: Long = 0L
    private var repeatStartMs: Long = 0L

    private var startedAtNs = 0L
    private var pausedAtNs = 0L

    fun cursorStepIndex(): Int {
        val currentState = state
        if (currentState == PlaybackEngine.State.IDLE || stepEnds.isEmpty()) return 0
        val offset = currentOffset()
        val index = stepEnds.indexOfFirst { it > offset }
        val resolved = if (index >= 0) index else stepEnds.lastIndex
        return stepOrdinals.getOrElse(resolved) { 0 }
    }

    /** 当前一轮内的时间偏移（与 [cursorStepIndex] 同一口径）。 */
    private fun currentOffset(): Long {
        if (stepEnds.isEmpty()) return 0L
        val reference = if (state == PlaybackEngine.State.PAUSED && pausedAtNs > 0L) {
            pausedAtNs
        } else {
            System.nanoTime()
        }
        // 暂停时长一律扣引擎的账（[PlaybackEngine.pausedNanos]）：按本类发出暂停请求的
        // 时刻记会把「请求→片尾」那段落下来，且每次暂停都累加，高亮会越拖越偏。
        val elapsed = (reference - startedAtNs - (engine?.pausedNanos() ?: 0L))
            .coerceAtLeast(0L) / 1_000_000L
        return cycleOffset(elapsed)
    }

    /**
     * 到「下一个步骤边界」还有多少毫秒：用于把高亮推进定时到步边界，
     * 既不漏步也不空转。短于一帧（~16ms）的快步压到 [STEP_TICK_THROTTLE_MS]，
     * 但每次仍渲染当前精确步（[cursorStepIndex] 时间推导），不会停在几步之前。
     */
    private fun nextStepDelayMs(): Long {
        if (stepEnds.isEmpty()) return PROGRESS_TICK_MS
        return stepDelayToNextBoundary(stepEnds, currentOffset(), cyclePeriodMs, repeatStartMs)
            .coerceAtLeast(STEP_TICK_THROTTLE_MS)
    }

    /** 把「播放开始至今」的时长折算成一轮之内的时间偏移。 */
    private fun cycleOffset(elapsed: Long): Long {
        if (cyclePeriodMs <= 0L) return elapsed
        if (repeatStartMs > 0L && cyclePeriodMs > repeatStartMs) {
            // 脚本内自带无限循环：首轮播前缀 + 循环体一次，之后只循环稳态区间
            if (elapsed < cyclePeriodMs) return elapsed
            val period = cyclePeriodMs - repeatStartMs
            return repeatStartMs + (elapsed - cyclePeriodMs) % period
        }
        return elapsed % cyclePeriodMs
    }

    /** 当前一轮内的时间偏移（含暂停扣除）；对外暴露给坐标层算箭头进度用。 */
    private fun currentCycleOffset(): Long =
        if (stepEnds.isEmpty()) 0L else cycleOffset(currentOffsetRaw())

    /** 与 [currentOffset] 同口径，但不做再次取模（保留跨轮累计，便于按绝对 ms 定位步骤）。 */
    private fun currentOffsetRaw(): Long {
        val reference = if (state == PlaybackEngine.State.PAUSED && pausedAtNs > 0L) {
            pausedAtNs
        } else {
            System.nanoTime()
        }
        return (reference - startedAtNs - (engine?.pausedNanos() ?: 0L)).coerceAtLeast(0L) / 1_000_000L
    }

    /**
     * 当前正执行的 [ordinal] 步这一遍**在时间轴里的起始偏移（ms）**。
     *
     * 与 [indexSteps] 同一展开口径（含 repeat 与循环）：坐标层画箭头进度时，
     * 用 [cursorElapsedMs] 减去这个值即得该步本遍已走多久，再除以 durationMs 即 [0,1] 进度。
     *
     * 必须返回「当前这一遍」的起点，而不是该 ordinal 第一次出现的起点——
     * 步骤自身 [Step.repeat] > 1 时，同一步会在时间轴上占多个窗口（如 ordinal=2 在
     * 300ms、600ms 各走一遍），若每遍都取首遍 300ms，则第二遍会把已过去 600+ms
     * 误算成「已走 300ms 多」→ 进度瞬间越界、箭头提前到终点 / 直接不显示。这里
     * 用与 [cursorStepIndex] 完全同口径的扫描（同一 offset、同一边界）取「当前窗口」起点。
     *
     * 取不到（未播放 / 序号越界 / 已越出最后一步）返回 0。
     */
    fun stepOffsetMs(ordinal: Int): Long {
        if (stepEnds.isEmpty() || ordinal <= 0) return 0L
        val offset = currentOffset()
        // 找到「包含当前 offset 的窗口」或「已越过最后一步时取最后一步」；
        // 与 cursorStepIndex 同样先按 stepEnds 找首个 > offset 的边界。
        val boundaryIdx = stepEnds.indexOfFirst { it > offset }
        val resolvedIdx = if (boundaryIdx >= 0) boundaryIdx else stepEnds.lastIndex
        val ord = stepOrdinals.getOrElse(resolvedIdx) { 0 }
        // 当前窗口未必是 ordinal 这一步（可能还在前一步的尾巴上），
        // 但箭头只在 setActiveOrdinal(ordinal) 被调用时才有意义，那时 ordinal 就是当前步，
        // 直接取该窗口起点即可；边界外或序数对不上返回 0（让进度从 0 起步，不出负数）。
        if (ord != ordinal) return 0L
        return if (resolvedIdx == 0) 0L else stepEnds[resolvedIdx - 1]
    }

    /**
     * 播放开始至今已过去多少毫秒（含暂停扣除、跨轮累计），与 [stepOffsetMs] 同一时间轴。
     * 坐标层据此把箭头进度对齐到 [0,1]；未播放时返回 0。
     *
     * 这是「绝对播放时钟」（pause 时冻结、跨轮累计），箭头进度 = (本值 - 该步起点) / 时长，
     * 因此暂停时读数停住、恢复后接着走、循环到下一轮从头开始——与需求「速度由时长决定」
     * 以及「暂停/停止立刻消失」完全对齐（箭头窗口由播放状态而非墙钟控制）。
     */
    fun cursorElapsedMs(): Long = currentCycleOffset()

    // ── 控制命令 ────────────────────────────────────────────

    /**
     * 播放指定脚本。
     *
     * @param loopTimes <=0 表示无限循环（脚本内自带的无限循环优先）。必填：
     *   两个调用点都持有 [ScriptRepository.load] 折算好的次数。
     *   注意 [ScriptRepository.setLoopTimes] 内部仍会再读一次脚本文件
     *   （为把旧版本的 prefs↔文件 lp 归一化），那一次无法从本方法省掉。
     */
    fun play(script: Script, loopTimes: Int): Boolean {
        val timeline = try {
            ScriptCompiler.compile(script)
        } catch (t: Throwable) {
            notifyError(t.message ?: "脚本编译失败")
            return false
        }
        if (timeline.strokes.isEmpty()) {
            // 一条 stroke 都没有（空脚本 / 只有 wait）：跑起来界面闪一下就结束，
            // 用户只看到「点了播放什么都没发生」。实测空脚本 0 次触摸、0ms 内退出，
            // 因此不做「静默成功」，直接说清楚。
            notifyError(appContext.getString(R.string.toast_script_empty))
            return false
        }
        // 间隔小于通道下限的脚本会被系统拉长（见 A11yBackend.MIN_GAP_MS）：
        // 用户看到的「按 20ms 写、实际 50ms 跑」必须有一句解释，否则像是软件不听话。
        // 判定本身见 [ChannelLimit]：纯计算、有单测。整段比脚本更长是通道的物理下限，
        // 不是调度器能改的，所以只提示、不改时间轴。
        if (ChannelLimit.tighterThanMinGap(timeline.strokes, A11yBackend.MIN_GAP_MS)) {
            notifyNotice(appContext.getString(R.string.toast_gap_floor, A11yBackend.MIN_GAP_MS))
        }
        // 坐标是相对「录制时那块屏幕」归一化的。当前屏幕与脚本记录的方向/旋转/宽高比/dp 视口
        // 不一致时，同一个归一化点会落到完全不同的控件上（竖屏录的点在横屏下横向拉宽 2.2 倍；
        // dp 视口缩小则整体重排），用户看到的是「乱点」。宁可拒绝播放并说清楚，也不静默点错位置。
        val mismatch = ScreenMetrics.mismatch(appContext, script.screen)
        if (mismatch != null) {
            notifyError(appContext.getString(R.string.toast_screen_mismatch, mismatch))
            return false
        }
        // 上一次的停止过渡态不在这里清：真正起步（runStart 提交成功）后才清，
        // 否则重启等待期间会先按旧引擎的残状态把三角形换回双竖线。
        // 播放中再次点播放：引擎单实例只能先停后起，这里给一句可见反馈，
        // 否则用户只看到进度回到 1/N，像是「点了没反应」。
        val restarting = state != PlaybackEngine.State.IDLE
        repository?.lastPlayedId = script.id
        repository?.setLoopTimes(script.id, loopTimes)
        currentScriptId = script.id
        loopLimit = loopTimes
        lastErrorMessage = null
        // 起播即「这次运行重新开始」：不存在「还停在暂停」，旗标作废
        // （见 noticeStillPausedAfterModal；resume/stop 同）
        pausedForModal = false
        indexSteps(timeline, script)
        // 撤窗只发生在真正起步前一刻（见 runStart）：这里提前撤会在重启等待期间
        // 被 onExited 的「空闲补回」撤销，白折腾一轮窗口事务。
        startTimeline(timeline, loopTimes, playToken.incrementAndGet()) {
            if (restarting) notifyNotice(appContext.getString(R.string.toast_play_restart))
        }
        return true
    }

    /** 悬浮窗「播放」按钮：没有当前脚本时自动取上次播放/列表首项。 */
    fun playCurrent(): Boolean {
        val repo = repository ?: return false
        // 目标脚本与界面显示同源（[playTargetId]）：空闲时以「上次激活/播放」为准，
        // 列表里刚选中的脚本必须立刻成为播放目标，否则小窗名字与真正跑起来的脚本会不一致。
        val target = playTargetId
        if (target != null) {
            repo.load(target)?.let { return play(it, it.loopTimes) }
            // 目标读不出来：绝不改播另一条脚本。用户点的是 A，屏幕上按 B 的坐标点击
            // 比「什么都没发生」严重得多（点错位置可能带来真实损失）。
            if (repo.exists(target)) {
                // 文件在但解析失败（半截 JSON / 手改出错）：保留目标，让提示一直指向这条脚本
                notifyError(appContext.getString(R.string.toast_play_target_unreadable))
            } else {
                // 文件已经不在了（外部删除、上次保存没落地）：目标本身失去意义，
                // 先把指向它的引用清掉。界面随即按 [ScriptRepository.fallbackId] 显示
                // 列表首项，也就是下次按下真正会播的那条——不再有「显示 A 跑 B」。
                forgetTarget(target)
                notifyError(appContext.getString(R.string.toast_play_target_missing))
            }
            return false
        }
        val fallback = repo.fallbackId()?.let { repo.load(it) } ?: run {
            notifyError("没有可用脚本")
            return false
        }
        return play(fallback, fallback.loopTimes)
    }

    /** 清掉指向已不存在脚本的引用（播放目标 + 当前运行脚本）。 */
    private fun forgetTarget(id: String) {
        if (currentScriptId == id) currentScriptId = null
        if (repository?.lastPlayedId == id) repository?.lastPlayedId = null
        notifyState()
    }

    fun pause(): Boolean {
        val ok = engine?.submit(Command.Pause) ?: false
        if (ok) {
            // 命令要等 execute 返回才会被消费；催后端收尾，避免回调丢失时停在「播放中」。
            // 暂停不丢弃本片笔画：时间轴是冻结不是跳过，丢了这一笔用户脚本就少点一下。
            backend.requestAbort(dropPending = false)
            // 冻结期间不注入，没有必须醒着的理由：一暂停就放锁，恢复时再拿
            releaseWakeLock()
            // 冻结读数取「第一次按下暂停」那一刻：迷你条 + 音量键、以及模态上的每次
            // 触摸都会再催一次 pause，后到的不能把冻结时刻往后推。
            if (pausedAtNs == 0L) pausedAtNs = System.nanoTime()
            notifyState()
            syncStateLater()
        }
        return ok
    }

    fun resume(): Boolean {
        val ok = engine?.submit(Command.Resume) ?: false
        if (ok) {
            // 回放又跑起来了：不存在「还停在暂停」，旗标就此作废（见 noticeStillPausedAfterModal）
            pausedForModal = false
            // 冻结时长由引擎记账（PlaybackEngine.pausedNanos），这里只解冻
            pausedAtNs = 0L
            // 暂停时放掉的锁在这里补回，恢复后仍需醒着驱动 nanoTime 定时
            acquireWakeLock()
            notifyState()
            syncStateLater()
        }
        return ok
    }

    fun stop(): Boolean {
        // 先置 IDLE 对外呈现，再投递停止：否则界面要等引擎线程收尾才变回三角
        stopRequested = true
        // 停止后没有「还在暂停」这回事：旗标一并作废
        pausedForModal = false
        // 让已在等待的重启失效：停止之后不允许再有 runStart 自己跑起来
        playToken.incrementAndGet()
        val ok = engine?.submit(Command.Stop) ?: false
        // 停止一定有效：引擎侧中断线程，后端侧立刻作废本片未派发的笔画，两条路径任一生效即可
        backend.requestAbort(dropPending = true)
        // ok=false 只可能是引擎从未创建或被拒，那时这两个值本就为 0（startEngine 清过）
        pausedAtNs = 0L
        notifyState()
        // 引擎真正退出后补一次通知，把过渡态收干净
        syncStateLater()
        // 停止即释放 WakeLock：收尾期间用户已不需要精确节拍
        releaseWakeLock()
        return ok
    }

    /**
     * 音量键快捷触发：**只在运行中**生效，一律执行停止。
     *
     * 空闲 / 暂停 / 无脚本都返回 false，调用方把按键交还系统——这些状态下音量键
     * 不该被本应用拦截，更不该顺手起播或恢复（旧实现的「开始/暂停切换」已废弃）。
     */
    fun stopFromVolumeKey(): Boolean {
        if (state != PlaybackEngine.State.RUNNING) return false
        stop()
        return true
    }

    // ── 内部装配 ────────────────────────────────────────────

    /**
     * 建立步骤时间轴（供悬浮窗显示第 N 步）。
     *
     * 展开顺序与 [ScriptCompiler] 保持一致：Delay 也占时间轴，Loop 按次数重复体步骤，
     * 无限循环展开一次后终止（之后由 [cycleOffset] 折算稳态区间）。
     *
     * 这是**名义时间轴**，不含两处真实派发规则：
     * - 通道起点间隔下限（[A11yBackend.MIN_GAP_MS]）会让相邻起点间隔不足 50ms 的
     *   笔画整体后移，且后移量**逐片累加、无上界**——引擎按绝对时限派发，落后的片
     *   会被 catch-up 吃掉名义差额，亏损却固化进墙钟；
     * - 因此密集脚本（相邻起点 < 50ms）上高亮环会在一巡内空转多圈，而「第 N 巡」
     *   读数是引擎真值、不受影响。起播时已弹过下限提示（[ChannelLimit]）。
     *
     * 不修：真实派发是「≥ 下限」而非「= 下限」（每笔还有派发抖动），app 侧任何模型
     * 只能是近似；要精确就得把后移与稳态周期闭包搬进 UI 层 = 第二套调度模型。
     */
    private fun indexSteps(timeline: Timeline, script: Script) {
        val ends = ArrayList<Long>()
        val ordinals = ArrayList<Int>()
        val numbers = IdentityHashMap<Step, Int>()
        var next = 0
        fun number(list: List<Step>) {
            for (step in list) {
                numbers[step] = ++next
                if (step is Step.Loop) number(step.body)
            }
        }
        number(script.steps)

        var elapsed = 0L
        var terminated = false
        fun walk(list: List<Step>) {
            for (step in list) {
                if (terminated) return
                if (step is Step.Loop) {
                    if (step.times == 0) {
                        walk(step.body)
                        terminated = true
                    } else {
                        repeat(step.times) { if (!terminated) walk(step.body) }
                    }
                    continue
                }
                val ordinal = numbers.getValue(step)
                // 动作类步骤展开与编译器一致：每轮都带动作延迟，循环次数完整展开
                repeat(step.repeat) {
                    val pre = Step.effectivePreDelay(step.preDelayMs, script.defaultActionDelayMs)
                    elapsed += pre + step.durationMs
                    ends.add(elapsed)
                    ordinals.add(ordinal)
                }
            }
        }
        walk(script.steps)

        stepEnds = ends.toLongArray()
        stepOrdinals = ordinals.toIntArray()
        cyclePeriodMs = timeline.durationMs
        repeatStartMs = timeline.repeatStartMs
    }

    /**
     * 引擎自然结束（脚本跑完 / 异常退出）时用户不会调 [stop]，因此 [notifyState]
     * 在 IDLE 且引擎真的退出后补一次释放，否则 WakeLock 会一直持到进程死亡——纯耗电泄漏。
     */
    private val powerManager: PowerManager? by lazy {
        appContext.getSystemService(PowerManager::class.java)
    }
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 播放期间持 PARTIAL_WAKE_LOCK：**假定**系统在休眠/Doze 下会挂起 CPU 定时器，
     * 而点击节拍靠 nanoTime 绝对时限，因此持锁以求节拍不被打断。
     * 停止/暂停/出错/跑完即释放，不在后台空转时耗电。
     *
     * 说明：上面那句「持锁能防住 Doze」在本项目里**没有实测证据**——验证机进不了 Doze
     * （`dumpsys deviceidle force-idle` 返回 not enabled，灭屏也不生效），
     * 只能确认锁被正确持有与释放（ACQ/REL 成对、暂停即放）；
     * 因此它是成本很低的保险，而不是已验证的保障。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autotap:playback") ?: return
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock != null && lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    private fun startTimeline(
        timeline: Timeline,
        loopTimes: Int,
        token: Int,
        onRestarted: () -> Unit,
    ) {
        val eng = playEngine()
        if (eng.isRunning) {
            // 引擎单实例：先停旧回放，等线程真正退出后再启动新的。
            eng.submit(Command.Stop)
            // 旧线程可能正卡在丢失回调的 execute 里，催后端收尾才能等到它退出；
            // 旧片未派发的笔画一并作废，否则新回放已在跑、旧坐标还在点。
            backend.requestAbort(dropPending = true)
            Thread({
                if (!eng.awaitIdle(2_000L)) {
                    // 旧线程 2s 还没退：硬起新线程会和它抢注入（同一 Service 单实例），
                    // 报错比「两条时间轴同时点」安全。
                    handler.post {
                        if (playToken.get() != token) return@post
                        notifyError("上一次回放未退出，请稍后重试")
                    }
                    return@Thread
                }
                handler.post {
                    // 等待期间可能来了新的播放或一次停止：只认自己那一代。
                    // 否则「点了停止它却自己跑起来」，或是两次播放都以「启动失败」收场。
                    if (playToken.get() != token) return@post
                    runStart(eng, timeline, loopTimes, token)
                    // 重启是异步的：提示必须等新线程真正起步后再给，
                    // 否则用户先看到「已重新开始」又看到错误提示。
                    onRestarted()
                }
            }, "autotap-restart").start()
        } else {
            runStart(eng, timeline, loopTimes, token)
            onRestarted()
        }
    }

    /** 引擎单实例；创建时挂上退出回调，见 [PlaybackEngine.onExited]。 */
    private fun playEngine(): PlaybackEngine = engine ?: PlaybackEngine(backend).also { created ->
        // 自然跑完 / 后端失效时没有命令可消费，必须由引擎主动通知一次界面收尾。
        // 顺带把引擎记录的失败原因报出去：后端断开、手势回调超时都只会静默停住，
        // 不报的话用户只看到「跑一半没了」。（手势被系统取消不算失败，见 A11yBackend。）
        created.onExited = {
            val error = created.lastError()
            handler.post {
                error?.let(::reportEngineError)
                notifyState()
            }
        }
        engine = created
    }

    /**
     * 撤窗 → 等窗口事务落到输入分发器 → 提交 Start。
     *
     * 为什么要等：圆点触摸窗必须**同步**撤掉（异步撤窗连第一击都拦不住，见
     * [CoordinateMarkersController.setPlaybackActive]），但系统把这条窗口事务交给
     * 输入分发器还要走 WMS → InputManager → InputDispatcher 几跳。撤窗与注入同一刻
     * 发生时，第一击会被「正在拆除」的窗吃掉——真机实测 26 次播放丢 6 次首击（最坏
     * 的「一跑一击」脚本 10 次丢 4 次），`InputDispatcher` 明确报
     * `com.auto.fairy will not receive the new gesture at (<点坐标>)`。
     *
     * 等多久不猜固定毫秒：固定值在快帧率设备上白等、在卡顿设备上不够。这里要求
     * **两帧真的过去**（帧慢就自动等更久），并保留 [PLAY_START_FLOOR_MS] 下限
     * （高刷屏两帧只有十几毫秒）、[PLAY_START_TIMEOUT_MS] 兜住「完全不产帧」。
     * 没有任何触摸窗可撤时（坐标层关闭 / 可拖关闭 / 脚本没有点）一步都不等：
     * 这条路径本来就不会被自己的窗吃掉；撤窗已经过去足够久时同样不等。
     */
    private fun runStart(eng: PlaybackEngine, timeline: Timeline, loopTimes: Int, token: Int) {
        if (CoordinateMarkersController.setPlaybackActive(true)) {
            windowTornDownAt = SystemClock.uptimeMillis()
        }
        // 撤窗已过去足够久（连续两次点播放、等待期间引擎才退出）：窗口树早就生效，不必再等
        val since = if (windowTornDownAt == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - windowTornDownAt
        if (since >= PLAY_START_FLOOR_MS) {
            if (playToken.get() == token) startEngine(eng, timeline, loopTimes)
            return
        }
        gateStart(token, PLAY_START_FLOOR_MS - since) { startEngine(eng, timeline, loopTimes) }
    }

    /**
     * 等「撤窗后的窗口树」生效再起步：时间下限 + 两帧，任一不满足都继续等。
     *
     * 停止/新播放会换掉 [playToken]，等待中的这一代就此作废（否则「点了停止它却自己
     * 跑起来」会经由这条延后路径复现）。不产帧（灭屏/无显示）时不能永久挂住起步：
     * 到点 + [PLAY_START_TIMEOUT_MS] 直接走，宁慢不卡。
     */
    private fun gateStart(token: Int, waitMs: Long, action: () -> Unit) {
        val gate = Any()
        startGate = gate
        var frames = 0
        var timeUp = false
        fun fire() {
            if (startGate !== gate) return
            startGate = null
            if (playToken.get() == token) action()
        }
        fun maybeFire() {
            if (timeUp && frames >= PLAY_START_FRAMES) fire()
        }
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (startGate !== gate) return
                frames++
                if (frames < PLAY_START_FRAMES) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    maybeFire()
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
        handler.postDelayed({
            if (startGate === gate) {
                timeUp = true
                maybeFire()
            }
        }, waitMs)
        handler.postDelayed({ if (startGate === gate) fire() }, waitMs + PLAY_START_TIMEOUT_MS)
    }

    private var startGate: Any? = null

    /** 最近一次「撤掉圆点触摸窗」的时刻（uptime）；0 表示本次进程内还没有撤过。 */
    private var windowTornDownAt = 0L

    private fun startEngine(eng: PlaybackEngine, timeline: Timeline, loopTimes: Int) {
        // 步骤读数的计时基准与实际启动对齐（重启/等窗期间等待过，不能用 play() 的时刻）
        startedAtNs = System.nanoTime()
        pausedAtNs = 0L
        // play() 已比过一次屏幕；但 runStart 的「撤窗→等两帧」窗口里屏幕仍可改，
        // 等窗结束才真正提交。窗口内改屏会让 prepare 采到新屏、旧坐标落到新屏。
        // 这里提交前再比一次，与 play() 同一口径，窗口内改屏直接拒播并说清差异。
        val mismatch = currentScriptId?.let { id -> repository?.load(id)?.screen }
            ?.let { ScreenMetrics.mismatch(appContext, it) }
        if (mismatch != null) {
            notifyError(appContext.getString(R.string.toast_screen_mismatch, mismatch))
            notifyState()
            return
        }
        var failure: String? = null
        val ok = try {
            eng.submit(Command.Start(timeline, loopTimes))
        } catch (t: Throwable) {
            failure = t.message ?: appContext.getString(R.string.toast_play_start_failed)
            false
        }
        // 起步成败都清「停止过渡态」：成功则这一轮已经真的在跑；被拒/抛异常时
        // 引擎里没有任何属于本次的播放，继续按 IDLE 呈现反而会让界面与实际脱节。
        stopRequested = false
        if (ok) {
            // WakeLock 只在真的跑起来之后才持：被拒绝/抛异常时若先持锁，就没人释放，
            // 界面停在 IDLE 却一直亮着 CPU 锁。失败分支由下面的 notifyState 统一释放。
            acquireWakeLock()
        } else {
            // 并发 Start 被拒绝（旧线程还没退干净）也必须可见：否则用户只看到
            // 循环数原地不动，误以为点了没反应。
            notifyError(failure ?: appContext.getString(R.string.toast_play_start_failed))
        }
        notifyState()
    }

    /** 引擎错误只上报一次：同一个错误不重复弹提示。 */
    private fun reportEngineError(t: Throwable) {
        val message = t.message ?: t.javaClass.simpleName
        if (lastErrorMessage == message) return
        lastErrorMessage = message
        deliverError(message)
    }

    /**
     * 错误出口：一个听众都没有时直接弹一句。
     *
     * 听众只有界面与浮层（主页列表、迷你条、坐标层、诊断页、脚本列表浮层），
     * 它们各自把 onError 变成 Toast/红字。用户点 ✕ 关掉会话、Back 退出应用后，
     * 进程仍被无障碍服务保活，音量键照样能起播——此时错误只剩诊断页有字，
     * 用户看到的是「跑一半突然停了，没有任何解释」（与 [detachBackend] 同型）。
     *
     * 这句是**尽力而为**，不是保证。AOSP 的判据是「有没有前台 Activity」：
     * 本应用的操作面全是悬浮窗，多数场景下自己没有 Activity 在前台，而 targetSdk 34 起
     * 「后台 + 未授予通知权限」的吐司会被系统静默丢弃。因为这个权限，
     * [com.auto.fairy.MainActivity] 首次启动会问一次（只问一次，拒绝后不再払扰）。
     * 授予后后台文本吐司恢复可用；仍然丢弃的情形（后台连发 3 条/20s 限流、
     * 自定义视图吐司）不影响本路径。权威记录始终是诊断页的 lastError。
     */
    private fun deliverError(message: String) {
        if (listeners.isEmpty()) {
            showToast(appContext, message)
        } else {
            listeners.forEach { it.onError(message) }
        }
    }

    // ── 监听 ────────────────────────────────────────────────

    /**
     * 注册后**立即以当前状态回调一次**（同步）。
     *
     * 调用方靠它拿到初始状态，不必再自己读一遍 PlaybackController 做初始化；
     * 因此也别在 addListener 之后紧跟一次手动 refresh——那会是第二次全量读。
     */
    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onPlaybackState(state, currentScriptId)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private var lastNotifiedState: PlaybackEngine.State? = null
    private var lastNotifiedScriptId: String? = null
    private var lastNotifiedHealth: Health? = null
    private var lastNotifiedError: String? = null

    private fun notifyState() {
        val current = state
        // 引擎真的退出（自然跑完 / 异常 / 停止收尾完成）后做一次收尾：
        // 释放 WakeLock、清停止过渡态、把圆点触摸窗按用户开关补回。
        // 引擎仍在跑（只是「已请求停止」）时什么都不做，界面继续按 IDLE 呈现。
        if (current == PlaybackEngine.State.IDLE && engine?.isRunning != true) {
            releaseWakeLock()
            stopRequested = false
            // 起播闸门开着（撤窗 → 等窗口事务 → 提交 Start 之间）不要补回圆点窗：
            // 起播那刻会再撤一次，撤窗与首笔只隔几十毫秒，InputDispatcher 直接丢弃
            // 整串手势（实测 78 次起播丢 28 次首击，加这道守卫后 80 次 0 丢）。
            if (startGate == null) CoordinateMarkersController.setPlaybackActive(false)
        }
        syncStepTicker()
        // 听众里全是「重读状态 + 重读仓库」的活：坐标层每条通知 load+解析整份脚本，
        // 脚本列表扫一遍目录；而暂停/起播/停止后各补 11 次状态同步，重复通知就是
        // 11 倍主线程 IO。键含 health（无障碍解绑/重连时播放状态不变，小窗红灰必须
        // 跟着变）与错误文案（起播失败时状态也不变，列表行必须翻红）。
        val healthNow = health()
        if (current == lastNotifiedState &&
            currentScriptId == lastNotifiedScriptId &&
            healthNow == lastNotifiedHealth &&
            lastErrorMessage == lastNotifiedError
        ) {
            return
        }
        lastNotifiedState = current
        lastNotifiedScriptId = currentScriptId
        lastNotifiedHealth = healthNow
        lastNotifiedError = lastErrorMessage
        listeners.forEach { it.onPlaybackState(current, currentScriptId) }
    }

    /**
     * 高亮环的推进节拍：定时到「下一个步骤边界」，一步一刷、不漏步也不空转。
     *
     * 巡数与步边界在同一条节拍线上取值（见 [emitStepAdvance]）：两者本就同一时刻
     * 才有意义，各走一条定时器只会让诊断页的巡数固定慢半拍。运行时按步边界排程；
     * 暂停时冻结（不清高亮，由状态通知按冻结时刻刷新一次）、停止/退出即撤销并把高亮清掉。
     */
    private fun syncStepTicker() {
        handler.removeCallbacks(stepTick)
        when (state) {
            PlaybackEngine.State.RUNNING -> {
                emitStepAdvance()
                handler.postDelayed(stepTick, nextStepDelayMs())
            }
            PlaybackEngine.State.IDLE -> {
                lastStepOrdinal = 0
                lastCycle = 0L
                CoordinateMarkersController.onPlaybackStepChanged()
            }
            else -> { /* PAUSED：冻结，等恢复后再排程 */ }
        }
    }

    private var lastStepOrdinal = 0
    private var lastCycle = 0L

    /**
     * 步边界回调：蓝环与巡数读数在**同一次取值**里更新，两者不再各走一条节拍。
     * 巡数只在真的变了才通知，列表侧的 onProgress 频次不高于「每巡一次」。
     */
    private fun emitStepAdvance() {
        val cycle = cursorCycle()
        if (cycle != lastCycle) {
            lastCycle = cycle
            listeners.forEach { it.onProgress(cycle) }
        }
        val ordinal = cursorStepIndex()
        if (ordinal != lastStepOrdinal) {
            lastStepOrdinal = ordinal
            CoordinateMarkersController.onPlaybackStepChanged()
        }
    }

    private val stepTick = object : Runnable {
        override fun run() {
            if (state != PlaybackEngine.State.RUNNING) return
            emitStepAdvance()
            handler.postDelayed(this, nextStepDelayMs())
        }
    }

    private fun notifyNotice(message: String) {
        listeners.forEach { it.onNotice(message) }
    }

    /**
     * 暂停/恢复命令是投递给引擎线程的，当前线程读到的 state 可能还是旧值；
     * 等引擎在切片边界真正切换后再补一次通知，否则悬浮窗会停在旧颜色。
     *
     * 引擎切换时刻取决于片长（≤400ms）与轮询节拍，单次延时容易正好落在切换之前，
     * 因此按节拍连补几次，确保界面在状态真正变化后不会被漏掉。
     */
    private fun syncStateLater() {
        handler.removeCallbacks(stateSync)
        stateSyncTries = 0
        handler.postDelayed(stateSync, STATE_SETTLE_MS)
    }

    private var stateSyncTries = 0

    private val stateSync = object : Runnable {
        override fun run() {
            notifyState()
            if (++stateSyncTries < STATE_SYNC_TRIES) {
                handler.postDelayed(this, STATE_SETTLE_MS)
            }
        }
    }

    private fun notifyError(message: String) {
        lastErrorMessage = message
        deliverError(message)
        notifyState()
    }

    /**
     * 可切换后端：无障碍服务可能被系统重连，但引擎必须保持单实例，
     * 因此用一层稳定代理把服务生命周期与引擎解耦。
     */
    private class DelegatingBackend : InputBackend {

        @Volatile
        var delegate: InputBackend? = null

        override val caps: Caps
            get() = delegate?.caps ?: Caps.ACCESSIBILITY

        override suspend fun prepare(): Result<Unit> =
            delegate?.prepare() ?: Result.failure(IllegalStateException(ERR_NOT_CONNECTED))

        override suspend fun execute(slice: Slice): Result<Unit> =
            delegate?.execute(slice)
                ?: Result.failure(IllegalStateException(ERR_NOT_CONNECTED))

        override fun health(): Health = delegate?.health() ?: Health.DEAD

        /** 催在途执行片收尾；后端不支持时是空操作（引擎侧中断仍然生效）。 */
        fun requestAbort(dropPending: Boolean) {
            (delegate as? SliceAbortable)?.requestAbort(dropPending)
        }
    }

    private const val STATE_SETTLE_MS = 150L

    /**
     * 起步前的等窗条件（见 [gateStart]）。
     *
     * 两帧覆盖「撤窗 → WMS → InputManager → InputDispatcher」这条链路；
     * 下限 48ms 兜住 120Hz 上两帧只有 ~17ms 的情况（实测 60Hz 下 48ms 已 0 丢）。
     */
    private const val PLAY_START_FRAMES = 2
    private const val PLAY_START_FLOOR_MS = 48L

    /** 完全不产帧（灭屏 / 无显示）时最长等多久：够久到几乎必然等掉窗口事务，又不卡住起步。 */
    private const val PLAY_START_TIMEOUT_MS = 300L

    /**
     * 状态补发次数：覆盖引擎在切片边界切换状态所需的最长时间。
     *
     * 暂停/停止只在片边界生效（execute 返回后才消费命令）。最坏形状是同片
     * 「19 个 d=1 点击 + 1 个 381ms 长按」：后移把末笔推到 950ms 派发，
     * 天然收尾 1331ms；回调丢失走后端宽限，末笔派发 + 600 = 1550ms。
     * 11 × 150 = 1650ms 盖住 1550，顺序取 N 的最小值——轮询在执行时才读状态，
     * 迟到只晚不漏，唯一失败模式是末次早于状态变化。
     */
    private const val STATE_SYNC_TRIES = 11

    /**
     * 高亮节拍的空时间轴兜底（见 [nextStepDelayMs]）：脚本没有可展开的步骤时
     * 按这个间隔重试，不参与巡数读数。
     */
    private const val PROGRESS_TICK_MS = 500L

    /**
     * 高亮推进的节流下限：步长短于一帧（~16ms）时人眼无法分辨单步，仍按此节拍刷，
     * 但每次都渲染当前精确步（[cursorStepIndex] 时间推导），不得停留在几步之前。
     */
    private const val STEP_TICK_THROTTLE_MS = 16L

}

/**
 * 模态载体共用的触摸闸门：回放中吞掉落在模态上的触摸。
 *
 * 对话框 / 面板 / 编辑器 / 悬浮浮层都要它：它们是**独立窗口**，宿主 Activity 的
 * `dispatchTouchEvent` 管不到；而 [PlaybackController.pauseForModal] 只停到片边界
 * （现象见 [PlaybackController.injecting]），片内还没派发的点击照旧落下来。
 * 实测（第二十六轮）：删除确认框开着时按音量键恢复播放，脚本的一笔注入直接点中
 * 「删除」，脚本当场被删（可复现）。
 *
 * 与 [MainActivity] 差一步：主页不是模态，它只吞注入、不暂停（见那里的注释），
 * 且要用 `deviceId` 把内核真触摸放行——模态则没有这个必要，用户主动打开的模态
 * 里不可能有"必须放行"的手指。
 *
 * @return true 表示已吞掉这一笔，调用方应直接返回 true。
 */
fun shieldModalTouch(event: MotionEvent): Boolean {
    if (!PlaybackController.injecting) return false
    // 先重新暂停（覆盖音量键恢复的路径），再丢弃这一串
    PlaybackController.pauseForModal()
    return true
}

/**
 * 对话框窗口的注入闸门：回放期间吞掉落到对话框上的触摸。
 *
 * 与 [PanelActivity]、[com.auto.fairy.editor.ScriptEditorActivity]、[MainActivity]、
 * [InjectShieldLayout] 是同一条不变式的载体之一，判定见 [shieldModalTouch]。
 * 返回键不走触摸路径，对话框照样能关。
 */
fun Dialog.shieldAgainstInjection() {
    val window = window ?: return
    val inner = window.callback ?: return
    window.callback = object : Window.Callback by inner {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (shieldModalTouch(event)) return true
            return inner.dispatchTouchEvent(event)
        }
    }
}

/**
 * 播放目标的取值链（小窗名字 / 坐标层 / 列表高亮 全靠它）。
 *
 * 运行或暂停：正在跑的脚本；空闲：上次播放的脚本，再退回运行过的脚本；
 * 都没有时用 [firstListedId]（列表首项）兜底。传入 null 即「不做列表回落」，
 * 供 [PlaybackController.playCurrent] 与加号追加这类**要动手**的调用方使用。
 *
 * 纯计算，不引用 PlaybackController 任何成员：触碰那个 object 会触发 Android
 * 静态初始化，JVM 单测跑不起来。
 */
internal fun resolvePlaybackTarget(
    playing: Boolean,
    currentId: String?,
    lastPlayedId: String?,
    firstListedId: String?,
): String? {
    val target = if (playing) currentId else lastPlayedId ?: currentId
    return target ?: firstListedId
}

/**
 * 距下一个步骤结束还有多少毫秒。稳态区间（repeatStart>0 且 period>repeatStart）下，
 * 一轮走完后接的是稳态首步，而非下一轮；取两者中更近的边界。纯计算，便于单测，
 * 不引用 PlaybackController 任何成员（否则触碰 object 会触发 Android 静态初始化，单测跑不起来）。
 */
internal fun stepDelayToNextBoundary(
    ends: LongArray,
    offset: Long,
    period: Long,
    repeatStart: Long,
): Long {
    val steady = if (repeatStart > 0L && period > repeatStart) period - repeatStart else 0L
    val nextEnd = ends.firstOrNull { it > offset } ?: return 500L
    var delay = nextEnd - offset
    if (steady > 0L) {
        // 当前落在稳态区间：下个边界可能是本循环体的下一步，也可能是下一轮首步
        val roundTail = repeatStart + steady - offset
        if (roundTail > 0L) delay = minOf(delay, roundTail)
    }
    return delay
}

package com.auto.fairy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.inject.Caps.Companion.ACCESSIBILITY_SLICE_SPAN_MS
import com.auto.fairy.core.inject.Health
import com.auto.fairy.core.inject.InputBackend
import com.auto.fairy.core.inject.LatencyClass
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.runtime.Slice
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 无障碍注入后端：把一个 [Slice] 打包成手势批次派发。
 *
 * ## 关键约束（设计方案 3.4 + 真机探针结论）
 * - 一次 dispatch 的 stroke 数 ≤ 20、总时长 ≤ 60s；
 * - **一次 dispatch 只承载一次按压**：`GestureDescription` 的多条 stroke 是同一手势的
 *   多根手指，只有时间上真正重叠（多指）的笔画才允许同批；前后接续的笔画各自派发
 *   （详见 [GestureBatching]）。实测：40 次相邻点击合进同一手势时只剩 1 次生效；
 * - 新 dispatch 会取消在途手势，必须等上一次 onCompleted/onCancelled 才发下一批，
 *   因此这里全程串行，绝不并发 dispatch。**反向也成立**：派发新一笔会作废用户正在进行的
 *   真触摸（按下后 34~149ms 收到 CANCEL，等不到 UP，实测同一坐标 `input tap` 却次次生效），
 *   所以迷你条的即时按钮挂在 ACTION_DOWN 上，见 `OverlayController.onPress`；
 * - **两次独立按压的起点间隔强制 ≥ 50ms**：MuMu（Android 12 / API 32）实测
 *   20ms 间隔会丢掉 1/3 的点击、50ms 间隔 5/5 轮精确命中。间隔不足时把后续动作
 *   整体后移（保持相对顺序），而不是照搬脚本时间轴。片内由 [GestureBatching] 后移，
 *   片间由 [dispatchBatch] 的「上一笔起点 + 上一笔时长」兜住；
 * - **真正的抖动来源是「余量 = 间隔 − 上一笔按住时长」**：下一笔必须等上一笔 `onCompleted`，
 *   余量小于回调延迟时，逐拍间隔被拆成「按完立刻派发」与「等下一个绝对栅格点」两种
 *   （实测 30Hz 模拟器 502 拍：间隔 60 / 按住 50 = 余量 10 → 主峰 51~52 与 66~67 的双峰，
 *   全域 50~95，均值 60.02、零漂移、零丢拍；按住 20 = 余量 40 → 基本恒 60ms，
 *   418 个间隔里 333 个恰为 60、sd 2.05）。与 vsync 无关：DOWN 时间戳不在 33.3ms 栅格上；
 *   也不是 [MIN_GAP_MS] 在起作用：起点间隔 ≥50，502 个里只有 2 个正好 50。
 *   下一拍按绝对栅格重算，误差不累积，
 *   所以「低刷屏把点击量化了」这个归因是错的。改不掉：新 dispatch 会取消在途手势，只能等；
 * - 片尾（slice.absoluteEndNs）之前不返回，遵守「阻塞到片尾」契约。
 *
 * ## 命令响应（D1/D2 修复）
 * 一个切片就是一个命令消费点，片长直接决定暂停/停止的生效延迟。这里通过
 * [Caps.maxSliceMs] 把片跨度压到 [ACCESSIBILITY_SLICE_SPAN_MS]（400ms），并在两条路径上兜底：
 * - 看门狗：`dispatchGesture` 的回调可能丢（丢没丢应用侧看不出来；第十七轮各落点的真实
 *   触摸都收到了回调），丢了就永远等不到 resume，引擎线程会永久 park。片尾 + 宽限
 *   仍未回调就按失败返回，让引擎走错误分支而不是假死。
 * - 提前结束：[requestAbort] 由控制器在暂停/停止时调用，给正常回调一个宽限期后
 *   主动结束本片，保证即使回调全丢，暂停/停止也能在宽限内生效。停止
 *   （`dropPending = true`）会把本片还没派发的笔画**当场作废**；暂停只收片、不丢笔画
 *   （时间轴是冻结而非跳过，丢了这一笔等于用户脚本少点一下）。**已在屏上的手势不归它管**：
 *   那是用户那一下触摸结束的——任何新触摸都会取消在途注入手势（实测 3s 滑动恰在触摸
 *   那一毫秒收到 CANCEL；用音量键暂停则整段仍跑满 3000ms，证明本方法不碰在途手势）。
 *   真实触摸无论落在哪个窗口（含自家迷你条，第十七轮两次实测）目标都会收到 CANCEL；
 *   `adb shell input` 注入的点击只有落在目标窗口内才截断那一笔，落在自家浮层时目标停在
 *   按下态、收不到 CANCEL（不会残留到下一次播放：实测重放的首个 DOWN 起落与时长精确）。
 *
 * ## 手势被系统取消 ≠ 回放失败（第十七轮实测）
 * 系统的 `MotionEventInjector` 一收到**真实**输入事件（触摸屏、鼠标按钮；`adb shell input`
 * 走的是注入通道，不进这条链）就作废在途手势：删掉还没发的 MotionEvent、给目标补一个
 * ACTION_CANCEL，并以 `onPerformGestureResult(sequence, false)` 回调服务。所以
 * 「播放中碰一下屏幕」必然让某一笔收到 onCancelled——把它当整轮失败时，用户看到的是
 * 播到一半自己停住 + Toast「手势被系统取消」。
 *
 * 实测（MuMu/API32，3s 长按脚本）：内核级触摸一下 → 0.28s 后回放线程消失；
 * 同一脚本用 `adb shell input tap` 打点 → 目标侧收到 CANCEL、那一笔被截断，回放照常。
 * 现在的处理是「这一笔丢了就丢了，接着派下一笔」：时间轴是绝对的，后续笔画本来就不受
 * 影响；目标已收到 ACTION_CANCEL，不会留下按下态。
 *
 * 第九、十轮都评估过「把长笔画切成 ≤400ms 续段，让停止落在段边界」：**不采纳**。
 * 它要新增分段状态机（明显增实体），收益只是让「停止时长按的收尾」更干净；
 * 而补偿性补发一次 UP 等于在用户屏幕上额外注入触摸，风险大于收益。
 * 当前行为已在此写明，避免后续重复分析。
 *
 * 说明：调度层不为相邻切片预留间隔（那会让「片内允许 0 间隔、片间强加 50ms」
 * 自相矛盾，实测 40 次 60ms 点击被拉成平均 67ms、每次片边界冒一个 90–106ms 的尖峰）。
 * 同一套下限由后端自己在每次按压之间兜住（见 [dispatchBatch]），片内片间口径一致。
 */
class A11yBackend(private val service: AccessibilityService) : InputBackend, SliceAbortable {

    private val main = Handler(Looper.getMainLooper())

    override val caps: Caps = Caps(
        maxSliceMs = ACCESSIBILITY_SLICE_SPAN_MS,
        // HIGH：采样/派发开销由系统管线承担（区别于 root 通道的 LOW）
        latencyClass = LatencyClass.HIGH,
    )

    @Volatile
    private var closed = false

    /** 当前执行片的主动结束入口；null 表示没有在途执行。 */
    @Volatile
    private var abortCurrent: (() -> Unit)? = null

    /** 在途执行代号：迟到的收尾只能清掉自己那一代，不能清掉后一代的入口。 */
    @Volatile
    private var abortGeneration = 0L

    /**
     * 停止已下达：本片尚未派发的笔画一律不再注入。
     *
     * 与 [abortCurrent] 的区别是「立刻、同步」：收尾宽限（[ABORT_GRACE_MS]）是给回调丢失准备的
     * 逃生门，片内下一笔往往在宽限之前就到了。实测（把坐标层关掉，注入的点击才不会被自家
     * 圆点触摸窗吞掉）：8 次停止里有 5 次在本片剩余笔画上又点了一下，最晚的一击在按下停止
     * 之后 236ms 才落到屏幕上——此时前台可能已经换成别的应用。
     *
     * **只给停止用，暂停绝不能用**（两条都会静默毁掉整条脚本）：
     * 1. 片内未派发的笔画被永久跳过——引擎游标已经越过本片，恢复后从下一片接；
     * 2. 只在 [prepare] 里复位，而 [prepare] 每次起播才调一次，`resume()` 不调——
     *    暂停时置 true 后，恢复后每片一进来就 [execute] 开头的 `dropped` 分支直接成功返回，
     *    脚本空转到结尾、一次都不点。
     */
    @Volatile
    private var dropped = false

    /**
     * 上一笔按压的派发时刻（**nanoTime 毫秒**）与时长，用于保证两次按压的**起点**间隔。
     *
     * 用 nanoTime 而不是 uptimeMillis：后者深睡停走，恢复后会少算间隔、
     * 把下一笔多推（有 floorMs 上限，但仍是本可避免的偏差）。与 desiredNs
     * 同基准也顺带消掉「两个时钟」的推理成本。
     */
    @Volatile
    private var lastPressStartMs = 0L

    @Volatile
    private var lastPressDurationMs = 0L

    /**
     * 本片末批的**真实**派发时刻（nanoTime）；0 = 还没派发。
     *
     * [ABORT_GRACE_MS] 的计划起点是「末笔计划派发时刻」，但主线程卡顿可以让真实派发
     * 整体晚于计划（片内链式 postDelayed 按 when 排序，卡顿结束时已到期的批次先跑）。
     * 只有末批真的发出之后才允许按宽限提前收片，否则剩余笔画会被 [finish] 置位的
     * `resumed` 挡掉——静默少点若干下，且引擎游标已越过本片，恢复后也不补。
     */
    @Volatile
    private var tailDispatchNs = 0L

    /**
    * 本轮回放起步时的真实屏幕尺寸。
    *
    * 归一化坐标只在「同一块屏幕」上成立：播放途中屏幕被换掉（横竖屏切换会由
    * 前台应用强制触发，例如打开本应用的编辑器），同一个归一化点会落到别处。
    * 真机实测：横屏脚本播放中点「编辑」→ 屏幕转竖 → 后续点击落到脚本列表的
    * 「删除」并弹出删除确认，用户完全没有察觉。宁可中止并报出两端尺寸。
    *
    * 旋转必须一起比：180° 反转时长宽完全不变，只比尺寸会整段漏判，之后每一笔
    * 都按镜像坐标注入。起播判定早已按同一条规则比旋转（[ScreenMetrics.mismatch]）。
    */
    @Volatile
    private var screenAtStart: ScreenInfo? = null

    override suspend fun prepare(): Result<Unit> {
        // 新一轮回放：上一轮残留的长按时长不能拿来当本轮的起点间隔（否则首个点击被推迟）
        lastPressStartMs = 0L
        lastPressDurationMs = 0L
        dropped = false
        // 换代：上一片可能被 stop 的 interrupt 兜底放弃（续体永不 resume ⇒
        // `stopped` 永远是 false），它在主线程队列里的批次会活到下一局。
        // 旧片截止时刻已过、起点间隔又被上面归零 ⇒ 会连发到旧片片尾。
        // 只有代次认得出这些残留（见 [dispatchBatch]）。
        abortGeneration++
        abortCurrent = null
        screenAtStart = ScreenMetrics.screenInfo(service)
        return if (closed) Result.failure(IllegalStateException(ERR_NOT_CONNECTED))
        else Result.success(Unit)
    }

    override suspend fun execute(slice: Slice): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException(ERR_NOT_CONNECTED))
        screenAbort(ScreenMetrics.screenInfo(service))?.let { return it }
        // 分批是纯计算（只读 slice），提前到挂起之前：下面两条宽限的基准是「最后一笔
        // 的派发时刻」，装配 abortCurrent 时就要拿到（片内后移可能让它比片尾晚上近 1s）。
        val batches = try {
            GestureBatching.build(slice.strokes, MIN_GAP_MS)
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        // 最后一笔的**计划派发时刻**（不是片计划跨度）：后移后两者可能差上近 1s
        val lastDispatchNs = slice.absoluteStartNs + batches.last().firstStartMs * 1_000_000L
        // 新一片：上一片的末批时刻作废
        tailDispatchNs = 0L
        return suspendCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            val generation = ++abortGeneration
            var watchdog: Runnable? = null
            val finish: (Result<Unit>) -> Unit = { result ->
                if (resumed.compareAndSet(false, true)) {
                    watchdog?.let { main.removeCallbacks(it) }
                    // 上一代被中断放弃的片可能在新一片开始后才收尾，不能清掉新片的入口
                    if (abortGeneration == generation) abortCurrent = null
                    continuation.resume(result)
                }
            }
            watchdog = Runnable {
                // 回调丢失：没有这道兜底，引擎线程会永久 park，暂停/停止全部失效。
                finish(Result.failure(IllegalStateException("手势回调超时")))
            }
            // 片真实收尾时刻 = max(片尾, 最后一笔派发完)：单笔长按只有前者，
            // 片内后移的密连只有后者。
            val endNs = maxOf(slice.absoluteEndNs, lastDispatchNs)
            val remainMs = (endNs - System.nanoTime()) / 1_000_000L
            main.postDelayed(watchdog, remainMs.coerceAtLeast(0L) + WATCHDOG_GRACE_MS)
            abortCurrent = {
                // 宽限从「本片最后一笔派发完」起算：暂停不丢笔的前提是片内笔画都已发出
                val waitMs = ((lastDispatchNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                main.postDelayed({
                    // 末批还没真正派出（主线程卡顿让真实派发晚于计划）：此刻收片会让
                    // 后面的笔画被 resumed 挡掉——静默少点若干下。交给正常收尾：
                    // 末批回调或看门狗兜底（那时本片笔画已全部发出）。
                    if (tailDispatchNs != 0L) finish(Result.success(Unit))
                }, waitMs + ABORT_GRACE_MS)
            }
            main.post {
                if (closed) {
                    finish(Result.failure(IllegalStateException(ERR_NOT_CONNECTED)))
                    return@post
                }
                if (dropped) {
                    finish(Result.success(Unit))
                    return@post
                }
                dispatchBatch(slice, batches, 0, finish, resumed, generation)
            }
        }
    }

    /**
    * 屏幕被换掉就返回失败（对照 [screenAtStart]）；null 表示还是起步时那块屏。
    *
    * 每片一次、**每笔派发前再一次**：片内笔画是按 absoluteStartNs 逐笔 postDelayed 派发的，
    * 片长可达 400ms。只在片首比一次，转屏后到下一片首之间的那几笔就会照旧用新屏重算像素
     * ——60ms 一笔的密集脚本一片能连错 6 笔。挪到派发前，最多错正在派的这一笔。
     *
     * 判据是**注入耦合**的：只有「片首」与「派发前」两个点会查。脚本里 `wait(60000)` 期间
     * 引擎停在 awaitDeadline、一笔都不派发，这里也就不查——改屏后要等到下一次注入
     * 才中止（最长 = 剩余等待时长）。这段时间没有注入，不会点错，只是「已经不能播了」
     * 而屏幕仍显示播放中。要提前，就得让 core 认识屏幕或复制一份判据，不值得。
     *
     * dp 也要一起比：`wm density` 变（设置里的「显示大小」）时 getRealSize 与旋转都不变，
     * 但 dp 视口变了、控件整体重排，归一化坐标同样会点错——起播门的
     * [ScreenMetrics.mismatch] 一直比 dp，播放中这道门漏了它。
     */
    private fun screenAbort(now: ScreenInfo): Result<Unit>? {
        val start = screenAtStart ?: return null
        if (now.w == start.w && now.h == start.h &&
            now.rot == start.rot && now.dpi == start.dpi
        ) {
            return null
        }
        return Result.failure(
            IllegalStateException(
                "屏幕已变为 ${now.w}×${now.h}@${now.rot * 90}°@${now.dpi}dpi" +
                    "（原 ${start.w}×${start.h}@${start.rot * 90}°@${start.dpi}dpi），回放中止",
            ),
        )
    }

    /**
    * 暂停/停止时由 [com.auto.fairy.PlaybackController] 调用：让在途执行片提前收尾，
    * 引擎线程才有机会消费命令。没有在途执行时是空操作。
    *
    * @param dropPending true = 停止：本片剩下的笔画立刻作废、不再注入；
    *   false = 暂停：本片照常跑完（片长 ≤400ms），笔画不丢——暂停恢复的是时间轴，
    *   丢掉这一笔等于用户脚本少点一下。
    */
    override fun requestAbort(dropPending: Boolean) {
        if (dropPending) dropped = true
        abortCurrent?.let { abort -> main.post { abort() } }
    }

    override fun health(): Health = if (closed) Health.DEAD else Health.ALIVE

    fun close() {
        closed = true
    }

    // ── 批次构建 ────────────────────────────────────────────

    // 批次划分规则见 [GestureBatching]：只有时间上重叠的笔画才进同一次手势。

    // ── 串行派发 ────────────────────────────────────────────

    private fun dispatchBatch(
        slice: Slice,
        batches: List<Batch>,
        index: Int,
        finish: (Result<Unit>) -> Unit,
        stopped: AtomicBoolean,
        generation: Long,
    ) {
        if (index >= batches.size) {
            resumeAtSliceEnd(slice, finish, Result.success(Unit))
            return
        }
        val batch = batches[index]
        // absoluteStartNs 由引擎在派发前写入，可直接当基准
        val desiredNs = slice.absoluteStartNs + batch.firstStartMs * 1_000_000L
        val delayMs = ((desiredNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        // 两次独立按压之间的**起点**间隔下限：不小于通道下限，也不小于上一笔按压时长
        // （同一次按压不能在上一笔还没抬起时开始）。这是「不早于」约束，不是固定延迟：
        // 计划间隔本来就够大时它恒为 0，脚本写多少就是多少。
        val sinceLastStart = System.nanoTime() / 1_000_000L - lastPressStartMs
        val floorMs = GestureBatching.startToStartFloorMs(lastPressDurationMs, MIN_GAP_MS)
        val gapDelayMs = if (lastPressStartMs == 0L) 0L else (floorMs - sinceLastStart).coerceAtLeast(0L)
        val finalDelayMs = maxOf(delayMs, gapDelayMs)
        val batchDurationMs = batch.strokes.maxOf { it.startMs - batch.firstStartMs + it.durationMs }

        main.postDelayed({
            if (closed) {
                finish(Result.failure(IllegalStateException(ERR_NOT_CONNECTED)))
                return@postDelayed
            }
            // 换代（新一轮 prepare）后旧片残留一律作废，见 [prepare]
            if (abortGeneration != generation) return@postDelayed
            if (stopped.get()) return@postDelayed
            // 停止已下达：本片剩余笔画不再注入，见 [dropped]
            if (dropped) {
                finish(Result.success(Unit))
                return@postDelayed
            }
            // 一次取值两处用：屏幕判据与像素换算必须同源，也省一次 binder IPC
            val screen = ScreenMetrics.screenInfo(service)
            screenAbort(screen)?.let {
                finish(it)
                return@postDelayed
            }
            val description = try {
                buildGesture(batch, Point(screen.w, screen.h))
            } catch (t: Throwable) {
                finish(Result.failure(IllegalStateException("手势参数超出系统上限", t)))
                return@postDelayed
            }
            // 单笔长手势的完整路径：被系统提前结束时按比例取剩余段续上（见 [tailPath]）
            val fullPath = if (batch.strokes.size == 1 && batchDurationMs > RESUME_MIN_PLAN_MS) {
                toPixelPath(batch.strokes[0].points, Point(screen.w, screen.h))
            } else {
                null
            }

            /**
             * 派发一次按压；[spentMs] 是本笔已经走过的时长（续接时累加）。
             *
             * 回调早于计划结束 = 这一笔被系统截断（部分 ROM 对超长手势有上限）。
             * 此时从走过的比例处取剩余路径、按剩余时长续派，总行程仍是脚本设定的值。
             * 没有被截断时（[RESUME_TOLERANCE_MS] 内）与从前完全一致。
             */
            fun dispatchOne(one: GestureDescription, spentMs: Long, resumeCount: Int): Boolean {
                val fromNs = System.nanoTime()
                val ok = try {
                    service.dispatchGesture(
                        one,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) = settle()

                            override fun onCancelled(gestureDescription: GestureDescription?) = settle()

                            private fun settle() {
                                if (stopped.get()) return
                                if (abortGeneration != generation) return
                                val elapsed = spentMs + (System.nanoTime() - fromNs) / 1_000_000L
                                val tail = if (fullPath != null && resumeCount < MAX_RESUME &&
                                    elapsed + RESUME_TOLERANCE_MS < batchDurationMs
                                ) {
                                    tailPath(fullPath, elapsed.toFloat() / batchDurationMs)
                                } else {
                                    null
                                }
                                if (tail != null && batchDurationMs - elapsed >= 1L) {
                                    dispatchOne(
                                        GestureDescription.Builder()
                                            .addStroke(
                                                GestureDescription.StrokeDescription(
                                                    tail,
                                                    0,
                                                    batchDurationMs - elapsed,
                                                ),
                                            )
                                            .build(),
                                        elapsed,
                                        resumeCount + 1,
                                    )
                                } else {
                                    dispatchBatch(slice, batches, index + 1, finish, stopped, generation)
                                }
                            }
                        },
                        null,
                    )
                } catch (t: Throwable) {
                    finish(Result.failure(t))
                    false
                }
                if (!ok) finish(Result.failure(IllegalStateException("dispatchGesture 返回 false")))
                return ok
            }

            if (dispatchOne(description, 0L, 0)) {
                lastPressStartMs = System.nanoTime() / 1_000_000L
                lastPressDurationMs = batchDurationMs
                // 末批真正发出：此后才允许按宽限提前收片（见 abortCurrent）
                if (index == batches.lastIndex) tailDispatchNs = System.nanoTime()
            }
        }, finalDelayMs)
    }

    private fun buildGesture(batch: Batch, size: Point): GestureDescription {
        val builder = GestureDescription.Builder()
        for (stroke in batch.strokes) {
            // 起点/时长上限由 Scheduler.validate 保证；duration >= 1 由 GestureBatching.build 保证
            val start = stroke.startMs - batch.firstStartMs
            val duration = stroke.durationMs
            builder.addStroke(
                GestureDescription.StrokeDescription(toPixelPath(stroke.points, size), start, duration),
            )
        }
        return builder.build()
    }

    private fun toPixelPath(points: List<com.auto.fairy.core.model.Point>, size: Point): Path {
        val path = Path()
        val width = size.x.toFloat()
        val height = size.y.toFloat()
        val first = points.first()
        path.moveTo(first.x * width, first.y * height)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x * width, points[i].y * height)
        }
        // 单点 tap 只 moveTo：AOSP 对长度 0 的路径走原地触摸分支，
        // 补一个同位置 lineTo 不改变路径长度，属无意义防御
        return path
    }

    /**
     * 取手势路径的剩余段：从 [fraction] 处继续到终点。
     *
     * 只用于「系统提前结束长手势」的续接（见 [dispatchBatch]）。起点与上一段末尾重合，
     * 续派后目标看到的是一次连续按住，而不是重新按下。
     */
    private fun tailPath(full: Path, fraction: Float): Path? {
        val measure = PathMeasure(full, false)
        val length = measure.length
        if (length <= 0f) return null
        val tail = Path()
        if (!measure.getSegment(length * fraction.coerceIn(0f, 1f), length, tail, true)) return null
        return tail
    }

    /** 等片尾再唤醒引擎线程；最后一批手势提前完成时用 postAtTime 补齐。 */
    private fun resumeAtSliceEnd(
        slice: Slice,
        finish: (Result<Unit>) -> Unit,
        result: Result<Unit>,
    ) {
        val remainMs = (slice.absoluteEndNs - System.nanoTime()) / 1_000_000L
        if (remainMs <= 0L) {
            finish(result)
        } else {
            main.postAtTime({ finish(result) }, SystemClock.uptimeMillis() + remainMs)
        }
    }

    companion object {
        /**
         * 后端不可用的统一文案：delegate 为 null（服务没连上）与 [close] 后
         * （已断开）指的是同一件事，曾在前者写「未连接」、后者写「已断开」，
         * 同一故障两种说法。收敛到一处，改文案只改这里。
         */
        const val ERR_NOT_CONNECTED = "无障碍服务未连接"

        /**
        * 两次独立按压的**起点**间隔下限（不是「上批结束后再加」）。
        *
        * MuMu/API32 实测：20ms 间隔会丢点击，50ms 稳定；这是 A11y 通道的物理下限。
        * 与上一笔按压时长取较大者：同一次按压不可能在上一笔还没抬起时开始。
        * 计划间隔本来就够大时它不产生任何影响（见 [GestureBatching] / [dispatchBatch]）。
        */
        const val MIN_GAP_MS = 50L

        /**
        * 手势回调看门狗的宽限。
        *
        * 回调丢失是真实故障（在 MuMu 上播放期间点到本应用浮层即可复现），一旦丢失
        * 续体永不 resume，引擎线程永久 park。宽限取 1.5s：足够覆盖系统派发抖动，
        * 又能在用户察觉「卡住」之前把错误抛回引擎。
        *
        * 计时基准是**片真实收尾时刻** `max(片尾, 最后一笔派发时刻)`：片内后移让后者
        * 可能远超片尾，按片尾算会抢在暂停收尾之前误报超时。恒晚于 [ABORT_GRACE_MS] 600ms。。
        */
        const val WATCHDOG_GRACE_MS = 1_500L

        /**
         * 单笔长手势被系统提前结束时，续接剩余行程。
         *
         * 计划时长超过 [RESUME_MIN_PLAN_MS] 才接管；回调比计划早 [RESUME_TOLERANCE_MS]
         * 以上才算截断（正常回调动辄晚几毫秒）。每次续接都从已走过的比例处开始，
         * 累计时长按 [MAX_RESUME] 封顶，异常情况最多重派这么多次就如实报错结束。
         */
        const val RESUME_MIN_PLAN_MS = 1_000L
        const val RESUME_TOLERANCE_MS = 200L
        const val MAX_RESUME = 40

        /**
        * 暂停/停止请求「本片最后一笔已派发」之后的提前收尾宽限。
        *
        * 正常回调先到——片真实收尾 = max(片尾, 末笔派发 + 末笔时长)，而这里更早，
        * 所以这条路径空转。真正会走到的只有两种：回调丢失；或末笔还没抬起且
        * 距派发已过 600ms（此时本片提前收尾，在途手势不受影响——暂停后不再派发新笔）。
        *
        * **计时起点是最后一笔的派发时刻，不是暂停请求时刻**：片内笔画被
        * [GestureBatching] 按起点间隔下限后移后，真实派发跨度可远超片计划跨度
        * （上界 19×[MIN_GAP_MS] + 末笔时长 ≈ 1.3s；20 指上限决定 19 个间隔）。
        * 从请求时刻起算时，暂停会在剩余笔画之前结束本片，而续体已 resume，后续批次被
        * `stopped` 挡掉——静默少点若干下（实测形状：`tap(d=10, pd=0, rp=20)`）。
        *
        * 计划起点仍可能晚于真实派发（主线程卡顿 >600ms 时，已到期的批次会排在
        * 这条收尾之前先跑），所以宽限生效还要求末批**真的已派出**
        * （[tailDispatchNs] 非 0）；未派出时不抢跑，交给末批回调或看门狗收尾。
        * 单笔长按在 dispatchGesture 返回时即置位，仍是 600ms 生效。
        */
        const val ABORT_GRACE_MS = 600L
    }
}

/**
 * 支持「提前结束当前执行片」的注入后端。
 *
 * 暂停/停止是异步命令，只有 `execute` 返回、引擎线程回到主循环才会被消费；
 * 后端回调丢失时 `execute` 可能长期不返回，因此控制器需要能主动催它收尾。
 */
interface SliceAbortable {
    /**
     * @param dropPending true = 停止：尚未派发的笔画立刻作废；false = 暂停：本片跑完即可。
     */
    fun requestAbort(dropPending: Boolean)
}


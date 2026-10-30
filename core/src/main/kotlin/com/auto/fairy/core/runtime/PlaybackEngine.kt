package com.auto.fairy.core.runtime

import com.auto.fairy.core.compiler.Timeline
import com.auto.fairy.core.inject.InputBackend
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * 回放引擎：单线程 + 绝对时限 park，循环体内不分配对象。
 *
 * ## 时间基准
 * `deadline = anchorNs + offsetMs * 1e6`，anchorNs 是当前迭代时间轴 0 点的绝对时刻。
 * 每次切片都按绝对 deadline 等待，因此 `execute` 花多久（片长）都不会累积漂移；
 * 一旦某片超时，下一片会立刻补上（catch-up），而不是把误差往后传。
 *
 * ## 暂停/续播
 * 暂停只在切片边界生效（后端 execute 必须在片尾返回，无法安全打断在途手势）。
 * 进入暂停时记录时刻，恢复时把 anchorNs 平移整个暂停时长——时间轴上的相对关系
 * 不变，因此不会产生漂移。
 *
 * ## 线程/重入
 * - 单实例单线程：并发第二次 Start 由 [active] 直接拒绝（runToken 再次兜底）；
 * - 外部只能投递 [Command]，引擎在切片边界与唤醒点统一消费；
 * - 队列、切片均预分配，稳态回放不产生调度对象；每次调用 `execute` 只分配一个续体
 *   （O(1)/片，而非 O(1)/stroke）。这是接口形状带来的唯一固有分配，
 *   等待侧（线程挂起/唤醒）仍由可复用的 [CallSite] 承担。
 */
class PlaybackEngine(private val backend: InputBackend) {

    enum class State { IDLE, RUNNING, PAUSED }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }

    private val tokenSeq = AtomicLong(0L)

    /** 当前运行代次；旧线程发现代次变化后立刻退出。只给同模块单测观察用。 */
    @Volatile
    internal var runToken: Long = 0L
        private set

    private val active = AtomicBoolean(false)
    private val commands = ConcurrentLinkedQueue<Command>()

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var stopRequested = false

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var pauseReqNs = 0L

    @Volatile
    private var wakeRequested = false

    @Volatile
    private var lastError: Throwable? = null

    /**
     * 已完成的整段迭代数（0 起）。在**迭代边界**推进，而不是在派发时推进：
     * 界面「第 N 巡」直接由它 +1 得到，与时间轴上的步骤高亮同一时刻切换。
     * 若只在派发时记，脚本以长等待/长动作延迟开头时（实测 2s 前摇），
     * 新一巡已经开始但读数仍停在上一巡，两处读数互相矛盾。
     */
    @Volatile
    private var cursorCycle = 0L

    @Volatile
    private var cursorSlice = 0

    /**
     * 本次回放累计的暂停时长（纳秒）。
     *
     * 显示层的「第几步」用「墙钟 − 暂停时长」反推时间轴位置。暂停命令要到片边界
     * 才被消费（最长等一整个长按片），这本账只有引擎记得准；控制器若按自己发出
     * 暂停请求的时刻记，每次暂停都会把读数永久落后一段并逐次累加。
     */
    @Volatile
    private var pausedTotalNs = 0L

    private val callSite = CallSite()

    @Volatile
    private var pendingSlice: Slice? = null

    /**
     * 回放线程退出时回调（自然跑完 / 出错 / 收到停止都走 [runLoop] 的 finally），
     * 在回放线程上执行。
     *
     * 为什么需要它：引擎自己是唯一知道「已经停了」的地方，停止以外的结束路径
     * （脚本跑完、后端失效）没有任何命令可消费，界面若只靠命令回调刷新，
     * 会一直停在「播放中」。回调里只能投递到主线程，不能做耗时操作。
     */
    @Volatile
    var onExited: (() -> Unit)? = null

    private val prepareCall: suspend () -> Result<Unit> = { backend.prepare() }
    private val executeCall: suspend () -> Result<Unit> = {
        pendingSlice?.let { backend.execute(it) } ?: Result.success(Unit)
    }

    val isRunning: Boolean get() = active.get()

    val playbackState: State get() = state

    fun lastError(): Throwable? = lastError

    /**
     * 开始回放（非阻塞）。已有回放在跑时返回 false（并发 Start 只有第一次成功）。
     *
     * @param loopTimes 整条时间轴重复次数，<=0 表示无限。
     *   脚本自带无限循环（`loop(n=0)`）时同样按此上限收口：首轮跑前缀+循环体，
     *   之后每轮只跑稳态区间，累计 [loopTimes] 轮即停（不再无条件忽略上限）。
     * @param from 断点续播游标；目前 app 侧未接线（PlaybackController 始终传 null），
     *   仅测试与诊断读取 [cursor] 用到。null 表示从头开始。
     */
    fun start(timeline: Timeline, loopTimes: Int = 1, from: TimelineCursor? = null): Boolean {
        if (!active.compareAndSet(false, true)) return false
        lastError = null
        stopRequested = false
        pauseRequested = false
        pauseReqNs = 0L
        wakeRequested = false
        commands.clear()
        val limit = if (loopTimes <= 0) 0 else loopTimes
        cursorCycle = from?.cycle ?: 0L
        cursorSlice = from?.sliceIndex ?: 0
        pausedTotalNs = 0L
        state = State.RUNNING

        val schedule = try {
            val built = Scheduler(backend.caps).schedule(timeline)
            if (limit == 0 && built.first.durationMs <= 0L) {
                throw IllegalArgumentException("零时长脚本不能无限循环（会空转占满 CPU）")
            }
            built
        } catch (t: Throwable) {
            lastError = t
            state = State.IDLE
            active.set(false)
            throw t
        }

        val token = tokenSeq.incrementAndGet()
        runToken = token
        val thread = Thread({ runLoop(token, schedule, from, limit) }, "autotap-playback")
        thread.isDaemon = true
        worker = thread
        thread.start()
        return true
    }

    fun pause(): Boolean {
        if (!active.get()) return false
        // 冻结起点取请求时刻，不是命令被消费的时刻：本片还要跑完（长手势最长 600ms 宽限），
        // 这段按旧写法算成正常播放，时间轴会永久少一段（实测 3s 长按暂停少 601ms）。
        pauseReqNs = System.nanoTime()
        commands.add(Command.Pause)
        wake()
        return true
    }

    fun resume(): Boolean {
        if (!active.get()) return false
        commands.add(Command.Resume)
        wake()
        return true
    }

    /** 请求停止；在途切片仍会走完（execute 阻塞到片尾的契约决定）。 */
    fun stop(): Boolean {
        if (!active.get()) return false
        commands.add(Command.Stop)
        wake()
        // 兜底保险：后端回调丢失时（真实故障，见 A11yBackend 的看门狗说明），
        // 命令永远等不到消费窗口，线程会一直挂在 CallSite 上。
        // 中断把线程从 park 里拽出来，保证「停止一定有效」。
        worker?.interrupt()
        return true
    }

    /** 统一命令入口（悬浮窗/通知/Activity 只走这里）。 */
    fun submit(command: Command): Boolean = when (command) {
        is Command.Start -> start(command.timeline, command.loopTimes, command.from)
        Command.Pause -> pause()
        Command.Resume -> resume()
        Command.Stop -> stop()
    }

    /** 当前进度（用于诊断页读取；app 侧断点续播未接线）。 */
    fun cursor(): TimelineCursor = TimelineCursor(cursorCycle, cursorSlice)

    /**
     * 本次回放累计的暂停时长（纳秒，由回放线程累加）。
     *
     * 唯一的暂停账本：暂停命令要到片边界才被消费（[waitWhilePaused]），
     * 控制器按请求时刻记会与这里的真实冻结时长差一个片尾等待，且逐次累加。
     */
    fun pausedNanos(): Long = pausedTotalNs

    /** 等待回放线程退出；返回是否已停止。 */
    fun awaitIdle(timeoutMs: Long = 1_000L): Boolean {
        val thread = worker ?: return !active.get()
        thread.join(timeoutMs)
        return !thread.isAlive
    }

    // ── 运行线程 ────────────────────────────────────────────

    private fun runLoop(token: Long, schedule: Schedule, from: TimelineCursor?, limit: Int) {
        var cycle = from?.cycle ?: 0L
        var sliceIndex = from?.sliceIndex ?: 0
        var plan = if (cycle == 0L) schedule.first else schedule.steady
        var anchorNs = System.nanoTime()

        try {
            val prepared = callSite.run(prepareCall)
            if (prepared.isFailure) {
                recordError(prepared.exceptionOrNull())
                return
            }

            while (true) {
                if (runToken != token) return
                drain()
                if (stopRequested) return
                if (pauseRequested) {
                    val pausedNs = waitWhilePaused(token)
                    if (pausedNs < 0L) return
                    anchorNs += pausedNs
                    pausedTotalNs += pausedNs
                    continue
                }

                if (sliceIndex >= plan.slices.size) {
                    // 迭代边界：先走完尾部 delay，再换计划/计次。
                    val endNs = anchorNs + plan.durationMs * NANOS_PER_MS
                    if (endNs > System.nanoTime() && !awaitDeadline(endNs, token)) continue
                    anchorNs += plan.durationMs * NANOS_PER_MS
                    cycle++
                    // 巡数在边界推进，见 [cursorCycle]
                    cursorCycle = cycle
                    cursorSlice = 0
                    // 脚本级次数对「含无限循环的脚本」同样生效：按稳态周期计数，
                    // 到次数就停。否则界面写着「循环 3 次」却永远不停（实测 47s 仍在跑）。
                    if (limit > 0 && cycle >= limit) return
                    plan = schedule.steady
                    sliceIndex = 0
                    continue
                }

                val slice = plan.slices[sliceIndex]
                val plannedNs = anchorNs + slice.startMs * NANOS_PER_MS
                if (!awaitDeadline(plannedNs, token)) continue

                // 游标先于执行提交：崩溃后从本片开头重放（at-least-once）。
                cursorSlice = sliceIndex
                slice.absoluteStartNs = plannedNs
                slice.absoluteEndNs = plannedNs + slice.durationMs * NANOS_PER_MS
                slice.cycle = cycle
                pendingSlice = slice
                val result = callSite.run(executeCall)
                pendingSlice = null
                if (result.isFailure) {
                    recordError(result.exceptionOrNull())
                    return
                }
                sliceIndex++
            }
        } catch (t: Throwable) {
            // 中断是停止的正常路径（见 stop() 的兜底说明），不算错误
            recordError(t)
        } finally {
            pendingSlice = null
            state = State.IDLE
            active.set(false)
            // 先置 IDLE/非运行，回调里读到的状态才与界面一致；回调异常不得打死线程
            runCatching { onExited?.invoke() }
        }
    }

    /** 记录异常供诊断页展示；停止引起的中断不是错误，直接忽略。 */
    private fun recordError(t: Throwable?) {
        if (t == null || t is InterruptedException) return
        lastError = t
    }

    /**
     * 阻塞到绝对 deadline。返回 false 表示被命令唤醒（暂停/停止），调用方应先
     * 回到循环顶部消费命令再重算等待。
     */
    private fun awaitDeadline(deadlineNs: Long, token: Long): Boolean {
        while (true) {
            if (runToken != token) return false
            val remaining = deadlineNs - System.nanoTime()
            if (remaining <= 0L) return true
            if (wakeRequested) {
                wakeRequested = false
                return false
            }
            if (remaining > DeadlineTimer.spinPadNs) {
                LockSupport.parkNanos(remaining - DeadlineTimer.spinPadNs)
            }
            // remaining <= spinPadNs：自旋（见 DeadlineTimer 注释）。
        }
    }

    /** 返回暂停时长（纳秒）；<0 表示期间收到停止/代次变更。 */
    private fun waitWhilePaused(token: Long): Long {
        state = State.PAUSED
        // 从**请求时刻**起算：本片可能在请求之后还要跑几百毫秒才收尾，
        // 那段时间同样属于冻结区间，不能算成在播放。
        val pauseStartNs = if (pauseReqNs != 0L) pauseReqNs else System.nanoTime()
        while (true) {
            drain()
            if (runToken != token || stopRequested) return -1L
            if (!pauseRequested) break
            LockSupport.park()
        }
        state = State.RUNNING
        pauseReqNs = 0L
        return System.nanoTime() - pauseStartNs
    }

    private fun drain() {
        while (true) {
            when (commands.poll() ?: return) {
                is Command.Start -> Unit // Start 不进队列（由 start() 直接处理）
                Command.Pause -> pauseRequested = true
                Command.Resume -> pauseRequested = false
                Command.Stop -> stopRequested = true
            }
        }
    }

    private fun wake() {
        wakeRequested = true
        worker?.let { LockSupport.unpark(it) }
    }

    /**
     * 复用式挂起调用点：把 suspend 调用固定到引擎线程，完成时 unpark 引擎线程。
     *
     * 用无限期 park（而不是带超时的 parkNanos）等待完成：Windows 上带超时的
     * park 会被 15.6ms 定时器刻度放大，无限期 park 由 unpark 精确唤醒。
     *
     * 中断语义：[stop] 会中断本线程作为兜底，park 立即返回但不会抛异常。这里
     * 显式把中断转成一次失败的等待结果（并清掉中断标记，否则后续 park 会空转），
     * 让引擎照常走到 finally 清理状态。放弃等待后迟到的回调按「代次令牌」丢弃，
     * 不会把结果错记到下一次调用上。
     */
    private class CallSite {
        @Volatile
        private var done = false

        @Volatile
        private var outcome: Result<Unit>? = null

        @Volatile
        private var waiter: Thread? = null

        /** 本次等待的令牌；比较用的是对象身份，因此不会被下一轮复用。 */
        private var session: Any? = null

        /** 令牌校验与结果写入必须原子：否则「旧回调通过校验」与「新一轮重置状态」交错会串结果。 */
        private val lock = Any()

        fun run(block: suspend () -> Result<Unit>): Result<Unit> {
            val mine = Any()
            synchronized(lock) {
                session = mine
                done = false
                outcome = null
                waiter = Thread.currentThread()
            }
            block.startCoroutine(object : Continuation<Result<Unit>> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Result<Unit>>) {
                    val accepted = synchronized(lock) {
                        // 本次等待已被中断放弃：迟到的回调不能污染下一次调用
                        if (session !== mine) return@synchronized false
                        outcome = result.getOrNull()
                            ?: Result.failure(
                                result.exceptionOrNull() ?: IllegalStateException("挂起调用异常"),
                            )
                        done = true
                        return@synchronized true
                    }
                    if (accepted) waiter?.let { LockSupport.unpark(it) }
                }
            })
            while (!done) {
                LockSupport.park()
                if (Thread.interrupted()) {
                    return Result.failure(InterruptedException("回放线程被中断"))
                }
            }
            return outcome ?: Result.failure(IllegalStateException("挂起调用未返回结果"))
        }
    }
}

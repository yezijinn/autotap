package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 暂停/恢复时的读数口径：进度（第 N 步、箭头进度）由「参考时刻 − 起播 − 引擎暂停账本」算。
 *
 * 回归点：恢复请求发出后，Resume 要等片边界才被引擎消费，这段窗口里引擎仍是 PAUSED、
 * 账本也还没记入这段冻结。旧实现在发请求那一刻就清冻结标记，参考时刻立刻换成墙钟，
 * 读数前跳一整段暂停时长（最长一个片，约 400ms），等账本补上又跳回来。
 * PlaybackController 是依赖 Android Looper 的单例，JVM 单测无法实例化，
 * 因此这里锁住口径所在的两段纯函数。
 */
class PauseReadoutTest {

    private val startNs = 1_000_000_000L
    private val pauseAtNs = 3_000_000_000L

    /** 暂停中：参考时刻取冻结时刻，墙钟再走读数也不动。 */
    @Test
    fun `暂停中读数冻结在按下时刻`() {
        val reference = readoutReferenceNs(
            enginePaused = true,
            frozenAtNs = pauseAtNs,
            nowNs = pauseAtNs + 5_000_000_000L,
        )
        assertEquals(pauseAtNs, reference)
        assertEquals(2000L, elapsedMsAt(reference, startNs, 0L))
    }

    /** 关键回归：已发出恢复、引擎仍停在片边界前（state 仍是 PAUSED），读数不得前跳。 */
    @Test
    fun `恢复未生效时不前跳`() {
        val nowNs = pauseAtNs + 400_000_000L
        val reference = readoutReferenceNs(enginePaused = true, frozenAtNs = pauseAtNs, nowNs = nowNs)
        assertEquals(2000L, elapsedMsAt(reference, startNs, 0L))
    }

    /** 恢复生效那一刻：账本记入冻结段，读数与冻结值严格相等，随后按墙钟前进。 */
    @Test
    fun `恢复生效瞬间与冻结值接续`() {
        val resumeAtNs = pauseAtNs + 400_000_000L
        val frozenMs = elapsedMsAt(pauseAtNs, startNs, 0L)
        assertEquals(frozenMs, elapsedMsAt(resumeAtNs, startNs, resumeAtNs - pauseAtNs))
        assertEquals(2016L, elapsedMsAt(resumeAtNs + 16_000_000L, startNs, resumeAtNs - pauseAtNs))
    }

    /** 引擎确认 RUNNING（标记已清）：参考时刻回到墙钟。 */
    @Test
    fun `恢复后按墙钟读数`() {
        val nowNs = pauseAtNs + 400_000_000L
        assertEquals(nowNs, readoutReferenceNs(enginePaused = false, frozenAtNs = 0L, nowNs = nowNs))
    }

    /** 没有冻结标记（尚未置位 / 已清）：即便引擎报 PAUSED 也按墙钟读，不退回 0。 */
    @Test
    fun `无冻结标记时按墙钟`() {
        assertEquals(pauseAtNs, readoutReferenceNs(enginePaused = true, frozenAtNs = 0L, nowNs = pauseAtNs))
        assertEquals(pauseAtNs, readoutReferenceNs(enginePaused = false, frozenAtNs = pauseAtNs, nowNs = pauseAtNs))
    }

    /** 账本只记已结算段：未记入当前冻结，因此必须靠冻结参考时刻挡住这段增量。 */
    @Test
    fun `账本未记当前冻结时靠冻结参考时刻挡住增量`() {
        val nowNs = pauseAtNs + 200_000_000L
        assertEquals(2200L, elapsedMsAt(nowNs, startNs, 0L))
        assertEquals(2000L, elapsedMsAt(readoutReferenceNs(true, pauseAtNs, nowNs), startNs, 0L))
    }

    /**
     * 回归：首次暂停还没到片边界（state 仍是 RUNNING）时重复暂停，不得挪动冻结时刻。
     *
     * 旧实现按 `!wasPaused` 判断，这段窗口里每催一次 pause 就把冻结时刻往后推，
     * 读数随之倒退；同一动作在暂停生效后却是幂等的。
     */
    @Test
    fun `片边界前的重复暂停不挪动冻结时刻`() {
        val clock = PauseFreezeClock()
        clock.onPauseRequest(pauseAtNs)
        clock.onPauseRequest(pauseAtNs + 200_000_000L)
        assertEquals(pauseAtNs, clock.frozenAtNs)

        // 引擎在片边界确认 PAUSED 之后，迷你条/模态触摸再催几次同样不动
        clock.onPauseRequest(pauseAtNs + 300_000_000L)
        assertEquals(pauseAtNs, clock.frozenAtNs)
        assertEquals(2000L, elapsedMsAt(readoutReferenceNs(true, clock.frozenAtNs, pauseAtNs + 900_000_000L), startNs, 0L))
    }

    /**
     * 回归：pause() 提交后紧跟的那次状态通知里引擎仍是 RUNNING。
     *
     * 这个通知必须保留刚锁定的冻结时刻与「已发过暂停」位，否则紧接着的重复 pause
     * 又会被当成第一次，冻结时刻被改写；等引擎真的进 PAUSED，读数也就没有冻结值可用。
     */
    @Test
    fun `待生效窗口里的 RUNNING 通知不清冻结`() {
        val clock = PauseFreezeClock()
        clock.onPauseRequest(pauseAtNs)
        clock.onRunningConfirmed()
        assertEquals(pauseAtNs, clock.frozenAtNs)

        // 待生效窗口里重复暂停依旧幂等
        clock.onPauseRequest(pauseAtNs + 250_000_000L)
        assertEquals(pauseAtNs, clock.frozenAtNs)

        // 引擎随后确认 PAUSED：读数冻结在首次按下那一刻
        assertEquals(2000L, elapsedMsAt(readoutReferenceNs(true, clock.frozenAtNs, pauseAtNs + 700_000_000L), startNs, 0L))
    }

    /** 回归：恢复尚未生效（引擎仍 PAUSED）时再次暂停，必须开启新的冻结时刻。 */
    @Test
    fun `恢复未生效时再次暂停取新冻结时刻`() {
        val clock = PauseFreezeClock()
        clock.onPauseRequest(pauseAtNs)
        clock.onResumeRequest()
        // 恢复请求不清冻结值：引擎确认离开 PAUSED 前读数仍冻结在上一段
        assertEquals(pauseAtNs, clock.frozenAtNs)

        val secondPauseNs = pauseAtNs + 400_000_000L
        clock.onPauseRequest(secondPauseNs)
        assertEquals(secondPauseNs, clock.frozenAtNs)

        // 恢复被引擎确认（RUNNING）时不得抹掉刚取的新冻结时刻：它属于新的暂停请求
        clock.onRunningConfirmed()
        assertEquals(secondPauseNs, clock.frozenAtNs)
    }

    /** 引擎确认恢复完成：冻结段结束、读数回到墙钟；下一次暂停重新取时刻。 */
    @Test
    fun `引擎确认恢复后清冻结`() {
        val clock = PauseFreezeClock()
        clock.onPauseRequest(pauseAtNs)
        clock.onResumeRequest()
        assertEquals(pauseAtNs, clock.frozenAtNs)

        clock.onRunningConfirmed()
        assertEquals(0L, clock.frozenAtNs)

        val nextNs = pauseAtNs + 1_000_000_000L
        clock.onPauseRequest(nextNs)
        assertEquals(nextNs, clock.frozenAtNs)
    }

    /** 起播 / 停止 / 确认 IDLE：冻结与请求位无条件作废，之后重新计时。 */
    @Test
    fun `起播停止与空闲时复位冻结`() {
        val clock = PauseFreezeClock()
        clock.onPauseRequest(pauseAtNs)
        clock.reset()
        assertEquals(0L, clock.frozenAtNs)

        val nextNs = pauseAtNs + 500_000_000L
        clock.onPauseRequest(nextNs)
        assertEquals(nextNs, clock.frozenAtNs)
        clock.reset()
        assertEquals(0L, clock.frozenAtNs)
    }
}

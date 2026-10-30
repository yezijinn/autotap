package com.auto.fairy.core.compiler

import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCompilerTest {

    private fun tap(durationMs: Long = 10L, x: Float = 0.5f): Step =
        Step.Tap(listOf(Point(x, 0.5f)), durationMs)

    private fun compile(vararg steps: Step): Timeline = ScriptCompiler.compile(
        Script(
            id = "s_test",
            name = "test",
            screen = ScreenInfo.DEFAULT,
            steps = steps.toList(),
            defaultActionDelayMs = 0L,
        ),
    )

    @Test
    fun `动作延迟按各自取值生效且零值不留空档`() {
        // pd 混用：0 / 300 / 600 / -1（跟随脚本默认 100）
        val timeline = ScriptCompiler.compile(
            Script(
                id = "s_test",
                name = "test",
                screen = ScreenInfo.DEFAULT,
                steps = listOf(
                    Step.Tap(listOf(Point(0.3f, 0.3f)), 60L, preDelayMs = 0L),
                    Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, preDelayMs = 300L),
                    Step.Tap(listOf(Point(0.7f, 0.7f)), 60L, preDelayMs = 600L),
                    Step.Tap(listOf(Point(0.7f, 0.3f)), 60L, preDelayMs = Step.PRE_DELAY_DEFAULT),
                ),
                defaultActionDelayMs = 100L,
            ),
        )

        // 每笔的起点 = 上一笔结束 + 本笔 pd；pd=0 紧接上一笔抬起，不留空档
        assertEquals(listOf(0L, 360L, 1020L, 1180L), timeline.strokes.map { it.startMs })
        assertEquals(1240L, timeline.durationMs)
    }

    @Test
    fun `嵌套有限循环按乘法展开且偏移连续`() {
        val timeline = compile(
            Step.Loop(
                3,
                listOf(
                    tap(durationMs = 100L),
                    Step.Loop(2, listOf(tap(durationMs = 10L))),
                ),
            ),
        )

        assertEquals(9, timeline.strokeCount)
        assertEquals(
            listOf(0L, 100L, 110L, 120L, 220L, 230L, 240L, 340L, 350L),
            timeline.strokes.map { it.startMs },
        )
        assertEquals(360L, timeline.durationMs)
        assertFalse(timeline.isRepeating)
    }

    @Test
    fun `步骤自身重复按次数完整展开`() {
        // rp=3：每轮先推进 preDelay 再产出 stroke，三条首尾相接
        val timeline = compile(
            Step.Tap(listOf(Point(0.5f, 0.5f)), durationMs = 60L, preDelayMs = 100L, repeat = 3),
        )

        assertEquals(3, timeline.strokeCount)
        assertEquals(listOf(100L, 260L, 420L), timeline.strokes.map { it.startMs })
        assertEquals(480L, timeline.durationMs)
        assertFalse(timeline.isRepeating)
    }

    @Test
    fun `滑动步骤与点击步骤混排时各自独立且顺序不变`() {
        val timeline = compile(
            Step.Tap(listOf(Point(0.3f, 0.3f)), durationMs = 60L, preDelayMs = 100L),
            Step.Swipe(
                listOf(Point(0.3f, 0.5f), Point(0.7f, 0.5f)),
                durationMs = 300L,
                preDelayMs = 100L,
            ),
            Step.Tap(listOf(Point(0.7f, 0.7f)), durationMs = 60L, preDelayMs = 100L),
        )

        assertEquals(3, timeline.strokeCount)
        assertEquals(listOf(100L, 260L, 660L), timeline.strokes.map { it.startMs })
        assertEquals(720L, timeline.durationMs)
        assertFalse(timeline.isRepeating)
        // 滑动的路径保留全部途经点（首点按下、末点抬起）
        assertEquals(2, timeline.strokes[1].paths.single().size)
    }

    @Test
    fun `多指手势的所有手指共享同一起点与时长`() {
        val timeline = compile(
            Step.Multi(
                listOf(
                    listOf(Point(0.3f, 0.4f)),
                    listOf(Point(0.7f, 0.6f)),
                ),
                durationMs = 200L,
                preDelayMs = 100L,
            ),
        )

        // 两条手指轨迹编成同一条 stroke 的两个 path：同时按下、同时抬起
        assertEquals(1, timeline.strokeCount)
        assertEquals(100L, timeline.strokes[0].startMs)
        assertEquals(200L, timeline.strokes[0].durationMs)
        assertEquals(2, timeline.strokes[0].paths.size)
        assertEquals(300L, timeline.durationMs)
    }

    @Test
    fun `等待步骤只推进时间轴不产生笔画`() {
        val timeline = compile(
            Step.Tap(listOf(Point(0.3f, 0.3f)), durationMs = 60L, preDelayMs = 100L),
            Step.Wait(500L),
            Step.Tap(listOf(Point(0.7f, 0.7f)), durationMs = 60L, preDelayMs = 100L),
        )

        assertEquals(2, timeline.strokeCount)
        assertEquals(listOf(100L, 760L), timeline.strokes.map { it.startMs })
        // tap1(100+60) + wait(500) + tap2(100+60)
        assertEquals(820L, timeline.durationMs)
    }

    @Test
    fun `无限循环只展开一次并记录稳态周期`() {
        val timeline = compile(
            tap(durationMs = 100L),
            Step.Wait(50L),
            Step.Loop(0, listOf(tap(durationMs = 200L), Step.Wait(100L))),
        )

        // 编译期只保留一个重复单元，运行期由 Scheduler 的稳态计划循环。
        assertEquals(2, timeline.strokeCount)
        assertTrue(timeline.isRepeating)
        assertEquals(1, timeline.repeatStartIndex)
        assertEquals(150L, timeline.repeatStartMs)
        assertEquals(300L, timeline.repeatPeriodMs)
        assertEquals(450L, timeline.durationMs)
    }

    @Test
    fun `无限循环之后的步骤不可达被丢弃`() {
        val timeline = compile(
            Step.Loop(0, listOf(tap())),
            tap(durationMs = 500L),
            Step.Wait(1_000L),
        )

        assertEquals(1, timeline.strokeCount)
        assertEquals(10L, timeline.durationMs)
    }

    @Test
    fun `嵌套无限循环以内层为稳态单元`() {
        val timeline = compile(
            Step.Loop(
                0,
                listOf(
                    tap(durationMs = 100L),
                    Step.Loop(0, listOf(tap(durationMs = 30L))),
                ),
            ),
        )

        // 外层循环体：A 播一次后进入内层无限循环；稳态单元是内层 B。
        assertEquals(2, timeline.strokeCount)
        assertEquals(1, timeline.repeatStartIndex)
        assertEquals(30L, timeline.repeatPeriodMs)
    }

    @Test
    fun `无限循环体为空在编译期报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            compile(Step.Loop(0, emptyList()))
        }
    }

    @Test
    fun `无限循环体时长为零在编译期报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            compile(Step.Loop(0, listOf(Step.Wait(0L))))
        }
    }

    @Test
    fun `1000 次循环完整展开`() {
        val timeline = compile(
            Step.Loop(1_000, listOf(tap(durationMs = 5L), Step.Wait(5L))),
        )

        assertEquals(1_000, timeline.strokeCount)
        assertEquals(0L, timeline.strokes.first().startMs)
        assertEquals(9_990L, timeline.strokes.last().startMs)
        assertEquals(10_000L, timeline.durationMs)
    }

    @Test
    fun `delay 不产生笔画但推进时间轴`() {
        val timeline = compile(
            Step.Wait(1_000L),
            tap(durationMs = 60L),
            Step.Wait(1_000L),
            tap(durationMs = 60L),
        )

        assertEquals(2, timeline.strokeCount)
        assertEquals(listOf(1_000L, 2_060L), timeline.strokes.map { it.startMs })
        assertEquals(2_120L, timeline.durationMs)
    }

    @Test
    fun `动作延迟不做笔画但逐轮推进时间轴`() {
        val timeline = compile(
            Step.Tap(listOf(Point(0.5f, 0.5f)), 100L, preDelayMs = 1_000L, repeat = 2),
        )

        assertEquals(2, timeline.strokeCount)
        assertEquals(listOf(1_000L, 2_100L), timeline.strokes.map { it.startMs })
        assertEquals(2_200L, timeline.durationMs)
    }

    @Test
    fun `动作延迟为零时与旧行为一致`() {
        val timeline = compile(Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, preDelayMs = 0L, repeat = 1))

        assertEquals(1, timeline.strokeCount)
        assertEquals(0L, timeline.strokes.single().startMs)
        assertEquals(60L, timeline.durationMs)
    }

    @Test
    fun `pd150 d50 两秒恰好十次且首拍在 150`() {
        // 手算基准：一次占 150 + 50 = 200ms，2000 / 200 = 10 次；
        // 第 k 次按下于 150 + 200(k-1)，第 10 次 1950→2000。
        val once = compile(
            Step.Tap(listOf(Point(0.5f, 0.5f)), 50L, preDelayMs = 150L, repeat = 10),
        )
        assertEquals(10, once.strokeCount)
        assertEquals((0 until 10).map { 150L + 200L * it }, once.strokes.map { it.startMs })
        assertEquals(2_000L, once.durationMs)

        // 同样参数塞进无限循环体：稳态周期仍是 200ms，每轮首拍都重新计 pd
        val looped = compile(
            Step.Loop(0, listOf(Step.Tap(listOf(Point(0.5f, 0.5f)), 50L, preDelayMs = 150L))),
        )
        assertEquals(listOf(150L), looped.strokes.map { it.startMs })
        assertEquals(200L, looped.repeatPeriodMs)
        assertEquals(0L, looped.repeatStartMs)
        assertEquals(200L, looped.durationMs)
    }

    @Test
    fun `长动作延迟叠加到 9999 轮仍不溢出`() {
        // 界面/编码允许的最大组合：每轮先等 1 小时、共 9999 轮
        val timeline = compile(
            Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, preDelayMs = 3_600_000L, repeat = 9_999),
        )

        assertEquals(9_999, timeline.strokeCount)
        assertEquals(9_999L * 3_600_060L, timeline.durationMs)
        assertTrue(timeline.strokes.last().endMs <= timeline.durationMs)
    }

    @Test
    fun `编译产物互不重叠`() {
        val timeline = compile(
            Step.Loop(4, listOf(tap(durationMs = 20L), Step.Wait(30L))),
        )

        var previousEnd = 0L
        for (stroke in timeline.strokes) {
            assertTrue(stroke.startMs >= previousEnd)
            previousEnd = stroke.endMs
        }
    }

    @Test
    fun `totalMsWith 与编译产物时长相符`() {
        // 模型属性与编译器必须同一口径，否则「第 N 步」的时间轴推算会漂移
        val action = Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, preDelayMs = 500L, repeat = 3)
        assertEquals(action.totalMsWith(0L), compile(action).durationMs)

        val finiteLoop = Step.Loop(4, listOf(action, Step.Wait(100L)))
        assertEquals(finiteLoop.totalMsWith(0L), compile(finiteLoop).durationMs)

        // 无限循环只展开一次：一次迭代的占用就是编译产物的总时长
        val infiniteLoop = Step.Loop(0, listOf(Step.Wait(10L), action))
        assertEquals(infiniteLoop.totalMsWith(0L), compile(infiniteLoop).durationMs)
    }

    @Test
    fun `跟随默认动作延迟时 totalMs 不能算出负延迟`() {
        // pd = -1 是「跟随脚本默认」的哨兵：直接当毫秒数参与累加会得到负的动作延迟，
        // 把总时长算短（repeat 越大差得越多）。默认值未知时只能按 0 计，精确口径用 totalMsWith。
        val follow = Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, repeat = 3)
        assertEquals(Step.PRE_DELAY_DEFAULT, follow.preDelayMs)
        assertEquals(180L, follow.totalMsWith(0L))

        // 给定脚本默认 250ms：每轮 250+60，共 930ms，与编译器完全一致
        assertEquals(930L, follow.totalMsWith(250L))
        assertEquals(
            follow.totalMsWith(250L),
            ScriptCompiler.compile(
                Script(
                    id = "s",
                    name = "n",
                    screen = ScreenInfo.DEFAULT,
                    steps = listOf(follow),
                    defaultActionDelayMs = 250L,
                ),
            ).durationMs,
        )

        // 循环体内的哨兵同样按脚本默认展开
        val loop = Step.Loop(2, listOf(follow, Step.Wait(40L)))
        assertEquals(2 * (930L + 40L), loop.totalMsWith(250L))
    }
}

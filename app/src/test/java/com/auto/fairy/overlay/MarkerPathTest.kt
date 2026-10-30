package com.auto.fairy.overlay

import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标层的数据侧约定：摊平序号必须与播放器步骤编号同口径，按路径写回必须只改目标点。
 *
 * 这两件事都只在浮层拖动时用到，一旦错位就是「拖 A 点，B 点被改」这种静默数据损坏，
 * 所以在 app 侧补一组纯 JVM 单测。
 */
class MarkerPathTest {

    private val p0 = Point(0.1f, 0.1f)
    private val p1 = Point(0.2f, 0.2f)
    private val p2 = Point(0.3f, 0.3f)
    private val np = Point(0.6f, 0.7f)

    private fun script(vararg steps: Step) = Script(id = "s1", name = "t", steps = steps.toList())

    @Test
    fun `wait 与 loop 占序号但不画点`() {
        val marks = MarkerFlatten.of(
            listOf(
                Step.Tap(listOf(p0), durationMs = 60),
                Step.Wait(500),
                Step.Loop(times = 2, body = listOf(Step.Tap(listOf(p1), durationMs = 60))),
                Step.Tap(listOf(p2), durationMs = 60),
            ),
        )
        // Tap=1, Wait=2, Loop=3（自身不画点）, 体内 Tap=4, 末尾 Tap=5
        assertEquals(listOf("1", "3.1", "4"), marks.map { it.label })
        assertEquals(listOf(1, 4, 5), marks.map { it.ordinal })
    }

    @Test
    fun `swipe 起点终点同号 终点空心`() {
        val marks = MarkerFlatten.of(
            listOf(Step.Swipe(listOf(p0, p1), durationMs = 300)),
        )
        assertEquals(2, marks.size)
        assertEquals(listOf("1", "1"), marks.map { it.label })
        assertEquals(false, marks[0].hollow)
        assertTrue(marks[1].hollow)
    }

    @Test
    fun `multi 每条手指都画 且都能按路径写回`() {
        val step = Step.Multi(listOf(listOf(p0), listOf(p1)), durationMs = 60)
        val marks = MarkerFlatten.of(listOf(step))
        assertEquals(2, marks.size)

        val moved = script(step).replaceAt(marks[1].path, np)
        val multi = moved.steps[0] as Step.Multi
        assertEquals(p0, multi.points[0][0])
        assertEquals(np, multi.points[1][0])
    }

    @Test
    fun `循环体内写回只改目标点 其余步骤原样`() {
        val inner = Step.Tap(listOf(p1), durationMs = 60)
        val loop = Step.Loop(times = 3, body = listOf(inner))
        val tail = Step.Tap(listOf(p2), durationMs = 60)
        val marks = MarkerFlatten.of(listOf(loop, tail))
        assertEquals("1.1", marks[0].label)

        val moved = script(loop, tail).replaceAt(marks[0].path, np)
        val movedLoop = moved.steps[0] as Step.Loop
        assertEquals(np, (movedLoop.body[0] as Step.Tap).points[0])
        // 未命中的步骤必须原样保留（data class 复用同一实例即可证明没重建）
        assertSame(tail, moved.steps[1])
    }

    @Test
    fun `swipe 终点写回不影响起点`() {
        val step = Step.Swipe(listOf(p0, p1), durationMs = 300)
        val marks = MarkerFlatten.of(listOf(step))
        val moved = script(step).replaceAt(marks[1].path, np)
        val swipe = moved.steps[0] as Step.Swipe
        assertEquals(p0, swipe.points[0])
        assertEquals(np, swipe.points[1])
    }

    @Test
    fun `空脚本没有标记`() {
        assertTrue(MarkerFlatten.of(emptyList()).isEmpty())
    }

    @Test
    fun `无限循环之后的步骤不摊平`() {
        val marks = MarkerFlatten.of(
            listOf(
                Step.Loop(times = 0, body = listOf(Step.Tap(listOf(p0), durationMs = 60))),
                Step.Tap(listOf(p1), durationMs = 60),
            ),
        )
        // 编译器只展开体内一次并终止后续（ScriptCompiler.compileLoop），绘制必须同口径：
        // 否则那些点永不高亮，却还能拖动并提示「坐标已保存」
        assertEquals(listOf("1.1"), marks.map { it.label })
        assertEquals(listOf(2), marks.map { it.ordinal })
    }

    @Test
    fun `外层有限内层无限 只摊平一次 且不吃掉循环体内的点`() {
        val marks = MarkerFlatten.of(
            listOf(
                Step.Loop(
                    times = 2,
                    body = listOf(
                        Step.Tap(listOf(p0), durationMs = 60),
                        Step.Loop(times = 0, body = listOf(Step.Tap(listOf(p1), durationMs = 60))),
                        Step.Tap(listOf(p2), durationMs = 60),
                    ),
                ),
            ),
        )
        // 1.2.1 保留（体内先摊平），1.3 丢弃（无限循环之后不可达）
        assertEquals(listOf("1.1", "1.2.1"), marks.map { it.label })
        assertEquals(listOf(2, 4), marks.map { it.ordinal })
    }
}

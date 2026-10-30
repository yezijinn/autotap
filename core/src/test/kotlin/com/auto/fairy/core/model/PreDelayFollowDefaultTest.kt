package com.auto.fairy.core.model

import com.auto.fairy.core.compiler.ScriptCompiler
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「跟随默认」动作延迟的语义锁定。
 *
 * 需求：步骤没被显式改过动作延迟时，改脚本的默认动作延迟要整体跟着变；
 * 显式填过数值的步骤保持不动。
 */
class PreDelayFollowDefaultTest {

    private fun script(ad: Long, steps: List<Step>) =
        Script(id = "s", name = "n", steps = steps, defaultActionDelayMs = ad)

    @Test
    fun `跟随默认的步骤随脚本默认值变动`() {
        val steps = listOf(
            Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, preDelayMs = Step.PRE_DELAY_DEFAULT),
        )

        // 默认 100：第一笔点击在 100ms
        val t1 = ScriptCompiler.compile(script(100L, steps))
        assertEquals(100L, t1.strokes.single().startMs)

        // 改成 800：同一脚本（步骤没动）第一笔点击在 800ms
        val t2 = ScriptCompiler.compile(script(800L, steps))
        assertEquals(800L, t2.strokes.single().startMs)
    }

    @Test
    fun `显式数值的步骤不跟随默认值变动`() {
        val steps = listOf(
            Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, preDelayMs = 500L),
        )
        val t1 = ScriptCompiler.compile(script(100L, steps))
        assertEquals(500L, t1.strokes.single().startMs)
        val t2 = ScriptCompiler.compile(script(800L, steps))
        assertEquals(500L, t2.strokes.single().startMs)
    }

    @Test
    fun `混合脚本只有跟随默认的那几步变动`() {
        val steps = listOf(
            Step.Tap(listOf(Point(0.1f, 0.1f)), 60L, preDelayMs = Step.PRE_DELAY_DEFAULT),
            Step.Tap(listOf(Point(0.2f, 0.2f)), 60L, preDelayMs = 500L),
            Step.Tap(listOf(Point(0.3f, 0.3f)), 60L, preDelayMs = Step.PRE_DELAY_DEFAULT),
        )
        // 默认 100：起点 100 / 100+60+500=660 / 660+60+100=820
        val t1 = ScriptCompiler.compile(script(100L, steps))
        assertEquals(listOf(100L, 660L, 820L), t1.strokes.map { it.startMs })

        // 默认改 800：第 1、3 步推迟，第 2 步不变
        // 800 / 800+60+500=1360 / 1360+60+800=2220
        val t2 = ScriptCompiler.compile(script(800L, steps))
        assertEquals(listOf(800L, 1360L, 2220L), t2.strokes.map { it.startMs })
    }

    @Test
    fun `序列化保持跟随默认哨兵值`() {
        val s = script(
            100L,
            listOf(
                Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, preDelayMs = Step.PRE_DELAY_DEFAULT),
                Step.Tap(listOf(Point(0.5f, 0.6f)), 60L, preDelayMs = 250L),
            ),
        )
        val back = Script.fromJson(s.toJson())
        assertEquals(Step.PRE_DELAY_DEFAULT, (back.steps[0] as Step.Tap).preDelayMs)
        assertEquals(250L, (back.steps[1] as Step.Tap).preDelayMs)
    }

    @Test
    fun `旧文件没有 pd 字段时按跟随默认解析`() {
        val text = """{"v":1,"id":"x","name":"n","ad":300,"steps":[
            {"t":"tap","p":[[0.5,0.5]],"d":60}
        ]}"""
        val tap = Script.fromJson(text).steps.single() as Step.Tap
        assertEquals(Step.PRE_DELAY_DEFAULT, tap.preDelayMs)
        // 编译时取脚本默认 300
        val t = ScriptCompiler.compile(Script.fromJson(text))
        assertEquals(300L, t.strokes.single().startMs)
    }
}

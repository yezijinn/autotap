package com.auto.fairy.core.model

import com.auto.fairy.core.compiler.ScriptCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `multi` 的线上格式是三层：`p` = 手指轨迹列表，每条轨迹 = 点列表，每个点 = `[x, y]`。
 *
 * 少写一层（把一条轨迹写成 `[x, y]`）时解码会抛「坐标必须是 [x, y] 数组」，
 * 而仓库读取侧用的是 `runCatching{}.getOrNull()`——脚本会静默从列表里消失。
 * 这条测试把格式钉住，避免再手写错一层。
 */
class MultiPathCodecTest {

    private fun script(steps: String) = """
        {
          "v": 1,
          "id": "s_multi",
          "name": "多指",
          "lp": 1,
          "ad": 100,
          "screen": {"w":1440,"h":3200,"dpi":560,"rot":0},
          "steps": [$steps]
        }
    """.trimIndent()

    @Test
    fun `多指编码后可以原样解码`() {
        val original = Script(
            id = "s_multi",
            name = "多指",
            steps = listOf(
                Step.Multi(
                    points = listOf(
                        listOf(Point(0.2f, 0.3f)),
                        listOf(Point(0.5f, 0.3f), Point(0.5f, 0.7f)),
                    ),
                    durationMs = 300L,
                    preDelayMs = 250L,
                ),
            ),
        )
        val decoded = Script.fromJson(original.toJson())
        assertEquals(1, decoded.steps.size)
        val step = decoded.steps.single() as Step.Multi
        assertEquals(2, step.points.size)
        assertEquals(listOf(Point(0.2f, 0.3f)), step.points[0])
        assertEquals(listOf(Point(0.5f, 0.3f), Point(0.5f, 0.7f)), step.points[1])
        assertEquals(300L, step.durationMs)
        assertEquals(250L, step.preDelayMs)
    }

    @Test
    fun `多指每条手指单点也要写成一层列表`() {
        val decoded = Script.fromJson(
            script("""{ "t": "multi", "d": 200, "pd": 150, "rp": 1,
                       "p": [[[0.25, 0.62]], [[0.75, 0.62]], [[0.5, 0.62]]] }"""),
        )
        val step = decoded.steps.single() as Step.Multi
        assertEquals(3, step.points.size)
        assertTrue(step.points.all { it.size == 1 })
        // 整组只有一个动作延迟：三根手指共享同一时刻
        val timeline = ScriptCompiler.compile(decoded)
        assertEquals(1, timeline.strokeCount)
        assertEquals(150L, timeline.strokes.single().startMs)
        assertEquals(3, timeline.strokes.single().paths.size)
    }

    @Test
    fun `动作延迟写亏一层会解码失败而不是静默变形`() {
        val bad = script("""{ "t": "multi", "d": 200, "pd": 0, "p": [[0.25, 0.62], [0.75, 0.62]] }""")
        val result = runCatching { Script.fromJson(bad) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ScriptFormatException)
    }
}

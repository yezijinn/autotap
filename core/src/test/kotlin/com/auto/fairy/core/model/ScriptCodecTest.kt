package com.auto.fairy.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCodecTest {

    private val sample = Script(
        id = "s_01H",
        name = "每日签到",
        screen = ScreenInfo(w = 1080, h = 2400, dpi = 420, rot = 0),
        steps = listOf(
            Step.Tap(listOf(Point(0.5f, 0.62f)), 60L),
            Step.Swipe(listOf(Point(0.5f, 0.8f), Point(0.5f, 0.2f)), 320L),
            Step.Multi(
                listOf(
                    listOf(Point(0.3f, 0.5f), Point(0.3f, 0.1f)),
                    listOf(Point(0.7f, 0.5f), Point(0.7f, 0.1f)),
                ),
                400L,
            ),
            Step.Wait(1_000L),
            Step.Loop(
                3,
                listOf(
                    Step.Tap(listOf(Point(0.1f, 0.1f)), 10L),
                    Step.Wait(20L),
                ),
            ),
        ),
     defaultActionDelayMs = 0L)

    @Test
    fun `输出是单份缩进格式且可往返`() {
        assertEquals(sample, Script.fromJson(sample.toJson()))
    }

    @Test
    fun `输出符合设计稿字段形状`() {
        val json = sample.toJson()
        assertTrue(json.contains("\"v\": 1"))
        assertTrue(json.contains("\"t\": \"tap\""))
        assertTrue(json.contains("\"p\": [[0.5, 0.62]]"))
        assertTrue(json.contains("\"t\": \"multi\""))
        assertTrue(json.contains("\"t\": \"loop\""))
        assertTrue(json.contains("\"n\": 3"))
    }

    @Test
    fun `动作延迟与单步循环往返一致`() {
        val script = Script(
            id = "s_pd",
            name = "延迟与循环",
            steps = listOf(
                Step.Tap(listOf(Point(0.5f, 0.5f)), 60L, preDelayMs = 1_000L, repeat = 3),
                Step.Swipe(
                    listOf(Point(0.5f, 0.8f), Point(0.5f, 0.2f)),
                    300L,
                    preDelayMs = 250L,
                    repeat = 2,
                ),
            ),
            loopTimes = 5,
            defaultActionDelayMs = 800L,
        )
        val json = script.toJson()
        assertTrue(json.contains("\"pd\": 1000"))
        assertTrue(json.contains("\"rp\": 3"))
        assertTrue(json.contains("\"lp\": 5"))
        assertTrue(json.contains("\"ad\": 800"))
        assertEquals(script, Script.fromJson(json))
    }

    @Test
    fun `旧格式缺少 pd 与 rp 时按无延迟只执行一次解析`() {
        val text = """
            {"v":1,"id":"x","name":"n","steps":[{"t":"tap","p":[[0.5,0.5]],"d":10}]}
        """.trimIndent()
        val tap = Script.fromJson(text).steps.single() as Step.Tap
        assertEquals(Step.PRE_DELAY_DEFAULT, tap.preDelayMs)
        assertEquals(1, tap.repeat)
        assertEquals(Script.DEFAULT_LOOP_TIMES, Script.fromJson(text).loopTimes)
        assertEquals(Script.DEFAULT_ACTION_DELAY_MS, Script.fromJson(text).defaultActionDelayMs)
    }

    @Test
    fun `旧写法 delay 解析为等待步骤`() {
        val text = """
            {"v":1,"id":"x","name":"n","steps":[{"t":"delay","d":1500},{"t":"wait","d":20}]}
        """.trimIndent()
        val steps = Script.fromJson(text).steps
        assertEquals(Step.Wait(1_500L), steps[0])
        assertEquals(Step.Wait(20L), steps[1])
    }

    @Test
    fun `等待步骤按 wait 写出`() {
        val script = Script(id = "w", name = "n", steps = listOf(Step.Wait(500L)), defaultActionDelayMs = 0L)
        assertTrue(script.toJson().contains("\"t\": \"wait\""))
        assertEquals(script, Script.fromJson(script.toJson()))
    }

    @Test
    fun `坐标越界被夹紧到 0 到 1`() {
        val text = """
            {"v":1,"id":"x","name":"n","steps":[{"t":"tap","p":[[-1.5, 2.0]],"d":10}]}
        """.trimIndent()
        val script = Script.fromJson(text)
        val tap = script.steps.single() as Step.Tap
        assertEquals(0f, tap.points[0].x, 0f)
        assertEquals(1f, tap.points[0].y, 0f)
    }

    @Test
    fun `未知字段被忽略`() {
        val text = """{"v":1,"id":"x","name":"n","future":true,"steps":[]}"""
        val script = Script.fromJson(text)
        assertEquals(0, script.steps.size)
    }

    @Test
    fun `非法 JSON 抛格式异常`() {
        assertThrows(ScriptFormatException::class.java) { Script.fromJson("{\"steps\":[}") }
        assertThrows(ScriptFormatException::class.java) { Script.fromJson("[]") }
        assertThrows(ScriptFormatException::class.java) { Script.fromJson("{\"steps\":[{\"t\":\"jump\"}]}") }
    }

    @Test
    fun `半截与缺少 steps 的文件按读取失败处理`() {
        // 播放侧用它区分「文件不存在」与「文件在但读不出」：两者都不许当成 0 步脚本继续播，
        // 早前会回落到列表里的另一条脚本，等于用户点 A、屏幕上按 B 的坐标点击。
        val full = """{"v":1,"id":"x","name":"n","steps":[{"t":"tap","p":[[0.5,0.5]],"d":10}]}"""
        assertThrows(ScriptFormatException::class.java) {
            Script.fromJson(full.substring(0, full.length / 2))
        }
        assertThrows(ScriptFormatException::class.java) { Script.fromJson("{\"v\":1}") }
        assertThrows(ScriptFormatException::class.java) { Script.fromJson("") }
    }

    @Test
    fun `高版本脚本被拒绝`() {
        assertThrows(ScriptFormatException::class.java) {
            Script.fromJson("{\"v\":2,\"id\":\"x\",\"name\":\"n\",\"steps\":[]}")
        }
    }

    @Test
    fun `空 steps 与默认屏幕可解析`() {
        val script = Script.fromJson("{\"id\":\"x\",\"name\":\"n\",\"steps\":[]}")
        assertEquals(ScreenInfo.DEFAULT, script.screen)
        assertEquals(Script.VERSION, script.v)
    }

    /** 旧脚本没有 rot 字段：解出「未知」，播放前的旋转比对该侧直接跳过。 */
    @Test
    fun `缺少 rot 字段解为未知旋转`() {
        val text = """{"v":1,"id":"x","name":"n","screen":{"w":1440,"h":3200,"dpi":560},"steps":[]}"""
        val screen = Script.fromJson(text).screen
        assertEquals(ScreenInfo.ROT_UNKNOWN, screen.rot)
        assertEquals(1440, screen.w)
    }

    @Test
    fun `带 UTF-8 BOM 的脚本可解析`() {
        val text = "\uFEFF" + """{"v":1,"id":"x","name":"n","steps":[{"t":"tap","p":[[0.5,0.5]],"d":10}]}"""
        val script = Script.fromJson(text)
        assertEquals("x", script.id)
        assertEquals(1, script.steps.size)
    }

    @Test
    fun `超范围的时长与循环次数被夹取`() {
        val text = """
            {"v":1,"id":"x","name":"n","ad":99999999,"steps":[
              {"t":"tap","p":[[0.5,0.5]],"d":99999999,"pd":-5,"rp":0},
              {"t":"swipe","p":[[0.5,0.5],[0.5,0.2]],"d":-100,"pd":99999999,"rp":99999999}
            ]}
        """.trimIndent()
        val script = Script.fromJson(text)
        assertEquals(3_600_000L, script.defaultActionDelayMs)

        val tap = script.steps[0] as Step.Tap
        assertEquals(3_600_000L, tap.durationMs)
        assertEquals(0L, tap.preDelayMs)
        assertEquals(1, tap.repeat)

        val swipe = script.steps[1] as Step.Swipe
        assertEquals(0L, swipe.durationMs)
        assertEquals(3_600_000L, swipe.preDelayMs)
        assertEquals(9_999, swipe.repeat)
    }
}

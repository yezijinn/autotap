package com.auto.fairy

import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 录制草稿的落盘/读回：进程被杀后要能原样接上。
 *
 * 只测文件层，不碰 Android 的 filesDir（用 [TemporaryFolder] 顶替）。
 */
class RecordDraftTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val screen = ScreenInfo(w = 1440, h = 3200, dpi = 560, rot = 0)

    private val steps = listOf(
        Step.Tap(listOf(Point(0.25f, 0.75f)), 60L),
        Step.Wait(300L),
        Step.Swipe(listOf(Point(0.5f, 0.8f), Point(0.5f, 0.2f)), 200L),
    )

    private fun filesDir(): File = tmp.newFolder("files")

    /** 步数、坐标、屏幕、名字都必须原样回来，否则恢复出来的脚本会点错位置。 */
    @Test
    fun `草稿写入后原样读回`() {
        val dir = filesDir()
        RecordDraft.save(dir, steps, screen, "录制 10-23 12:00")

        val draft = RecordDraft.load(dir)
        assertEquals(steps, draft?.steps)
        assertEquals(screen, draft?.screen)
        assertEquals("录制 10-23 12:00", draft?.name)
    }

    /** 半截 JSON：当成没有草稿，并把坏文件删掉，别让每次进录制页都读一遍。 */
    @Test
    fun `损坏的草稿读到 null 且被删除`() {
        val dir = filesDir()
        RecordDraft.file(dir).writeText("{")

        assertNull(RecordDraft.load(dir))
        assertFalse(RecordDraft.file(dir).exists())
    }

    /** 正常存盘或用户放弃后：文件不在了，也就不该再弹恢复。 */
    @Test
    fun `清除后文件与读回都没了`() {
        val dir = filesDir()
        RecordDraft.save(dir, steps, screen, "录制")
        assertTrue(RecordDraft.file(dir).isFile)

        RecordDraft.clear(dir)

        assertFalse(RecordDraft.file(dir).exists())
        assertNull(RecordDraft.load(dir))
    }

    /** 一步都没录：空草稿不算「中断的录制」。 */
    @Test
    fun `空步骤草稿视为没有草稿`() {
        val dir = filesDir()
        RecordDraft.save(dir, emptyList(), screen, "录制")

        assertNull(RecordDraft.load(dir))
        assertFalse(RecordDraft.file(dir).exists())
    }

    /** 草稿必须落在 files/ 根下：放进 files/scripts/ 会被仓库当成一条正常脚本。 */
    @Test
    fun `草稿文件在 files 根目录而不在 scripts 里`() {
        val dir = filesDir()
        val file = RecordDraft.file(dir)

        assertEquals(dir, file.parentFile)
        assertFalse(file.parentFile?.name == "scripts")
    }
}

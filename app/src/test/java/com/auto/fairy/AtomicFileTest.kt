package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 原子替换的回归测试：脚本正文与索引都靠它。
 *
 * 之前的 `writeText` 是「truncate 后逐段写」，写入途中被杀就留下半截文件，
 * 后续表现为「脚本从列表消失」或「所有脚本一起消失」，光靠人工看不出来。
 */
class AtomicFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `写入后内容完整且不留临时文件`() {
        val dir = tmp.newFolder("scripts")
        val dest = File(dir, "s_1.json")

        AtomicFile.write(dest, """{"v":1,"name":"中文"}""")

        assertEquals("""{"v":1,"name":"中文"}""", dest.readText())
        assertFalse("同目录不应残留临时文件", File(dir, "s_1.json.tmp").exists())
        assertEquals("目录里只应有目标文件", 1, dir.listFiles()?.size)
    }

    @Test
    fun `覆盖写不会留下旧内容的尾巴`() {
        val dir = tmp.newFolder("scripts")
        val dest = File(dir, "index.txt")
        dest.writeText("s_old_first\ns_old_second\ns_old_third")

        AtomicFile.write(dest, "s_new")

        assertEquals("s_new", dest.readText())
        assertFalse(File(dir, "index.txt.tmp").exists())
    }

    @Test
    fun `父目录里的临时文件与目标同目录`() {
        // rename 只有在同一文件系统内才是原子的：临时文件必须和目标同目录，
        // 不能落在系统临时目录（跨分区 rename 会失败并退化成非原子覆盖）
        val dir = tmp.newFolder("scripts")
        val dest = File(dir, "s_2.json")
        AtomicFile.write(dest, "x")
        assertTrue(dest.parentFile?.canonicalPath == dir.canonicalPath)
    }
}

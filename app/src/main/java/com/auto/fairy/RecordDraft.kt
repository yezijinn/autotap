package com.auto.fairy

import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import java.io.File

/**
 * 录制草稿：每录完一个手势覆写一次，进程被杀也能接着来。
 *
 * 存盘口径直接复用脚本 JSON（[Script.toJson]），不引入第二套格式；
 * 文件落在 files/ 而不是 files/scripts/ —— 仓库按目录扫 *.json，
 * 草稿放进去会被当成一条正常脚本。
 * 「是否中断」就是「文件在不在」：正常存盘/主动取消都删掉，不留标志位。
 */
internal object RecordDraft {

    private const val FILE_NAME = "record-draft.json"
    private const val DRAFT_ID = "draft"

    fun file(filesDir: File): File = File(filesDir, FILE_NAME)

    fun save(filesDir: File, steps: List<Step>, screen: ScreenInfo, name: String) {
        AtomicFile.write(
            file(filesDir),
            Script(
                id = DRAFT_ID, name = name, screen = screen,
                steps = ArrayList(steps), defaultActionDelayMs = 0L,
            ).toJson(),
        )
    }

    /** 不存在或损坏都返回 null；损坏文件顺手删掉，否则每次进录制页都要重新读坏文件。 */
    fun load(filesDir: File): Script? {
        val f = file(filesDir)
        if (!f.isFile) return null
        val script = runCatching { Script.fromJson(f.readText()) }.getOrNull()
        if (script == null || script.steps.isEmpty()) {
            runCatching { f.delete() }
            return null
        }
        return script
    }

    fun clear(filesDir: File) {
        runCatching { file(filesDir).delete() }
    }
}

package com.auto.fairy

import java.io.File
import java.io.FileOutputStream

/**
 * 文本文件的原子替换。
 *
 * 直接 `writeText` 是「truncate 后逐段写」：进程在写入中途被杀、或掉电，就留下半截文件。
 * 脚本正文与索引都吃过这个亏（半截 JSON = 脚本从列表消失；半截索引 = 所有脚本一起消失）。
 *
 * 做法是「同目录临时文件 → 刷盘 → rename」：同一文件系统内的 rename 是原子的，
 * 读者只会看到旧内容或新内容。刷盘给掉电兜底，否则 rename 生效但数据还在页缓存里，
 * 重启后可能读到 0 字节。
 *
 * 临时文件名固定为 `<name>.tmp`：全仓写脚本/索引都在这一个进程里、且调用方持有
 * 唯一的仓库锁，同一目标不存在并发写，因此不需要再拼随机后缀。
 */
internal object AtomicFile {

    fun write(dest: File, text: String) = write(dest, text.toByteArray(Charsets.UTF_8))

    fun write(dest: File, bytes: ByteArray) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (tmp.renameTo(dest)) return
            // rename 失败（个别 ROM / 残留占用）：退化为覆盖写，至少不丢内容
            dest.writeBytes(bytes)
        } finally {
            // rename 成功后 tmp 已不存在；失败路径上清掉，别在目录里留垃圾
            if (tmp.exists()) tmp.delete()
        }
    }
}

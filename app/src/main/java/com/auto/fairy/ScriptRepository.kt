package com.auto.fairy

import android.content.Context
import android.net.Uri
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Step
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 脚本仓库：文件即脚本（`files/scripts/<id>.json`）。
 *
 * - 脚本正文统一走 :core 的 [Script.toJson] / [Script.fromJson]，不重复实现编解码；
 * - `index.txt` 只保存展示顺序（每行一个 id），失效 id 在读取时自动剔除；
 * - 循环次数已迁进脚本文件（`lp` 字段），SharedPreferences 只作旧版本数据的兼容缓存：
 *   读时偏好优先（旧版本只写在那儿），写时两边同步，新脚本因此自带完整属性。
 */
class ScriptRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "scripts").apply { mkdirs() }
    private val indexFile = File(dir, "index.txt")
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val listeners = LinkedHashSet<() -> Unit>()

    data class Entry(
        val id: String,
        val name: String,
        val stepCount: Int,
        val loopTimes: Int,
        val modifiedAt: Long,
        /**
         * 文件在但读不出来（半截 JSON / 手改出错 / 无权限）。
         *
         * 这种脚本必须**看得见**：早前 `list()` 直接把它从列表里跳过，用户看到的是
         * 「脚本凭空消失」，而播放又一直报「读取失败」，没有任何可操作入口。
         */
        val broken: Boolean = false,
    )

    // ── 查询 ────────────────────────────────────────────────

    /**
     * 可展示的脚本清单。
     *
     * @param includeBroken 是否把「文件在但读不出」的条目也列出来。界面列表要传 true
     *   （否则损坏的脚本整行消失，用户无从处理）；播放/回落一律用默认 false ——
     *   那些路径只认能真正执行的脚本。
     */
    fun list(includeBroken: Boolean = false): List<Entry> = synchronized(lock) {
        val entries = ArrayList<Entry>()
        val kept = ArrayList<String>()
        // 索引只读一次：末尾「有没有变化」的比较用同一份快照，不必再读一遍文件
        val indexed = readIndex()
        for (id in indexed) {
            val file = fileOf(id)
            // 文件真没了：从索引里剔除（这是删除/失效 id 的唯一清理点）
            if (!file.isFile) continue
            // 文件还在但读不出来：只从列表隐藏，索引位置保留。
            // 否则一次读失败（写到一半、外部编辑到一半）就会把它从这里永久抹掉，
            // 修好文件后只能按修改时间回到列表末尾。
            kept.add(id)
            val script = readScript(file)
            if (script == null) {
                if (includeBroken) entries.add(brokenEntryOf(id, file))
                continue
            }
            entries.add(entryOf(id, script, file))
        }
        // 目录里存在但索引没记录的文件（例如手动拷入）：补到末尾，读取时自动接管。
        // 过滤放在排序之前：sortedByDescending 的比较器每次比较都要 stat（lastModified），
        // 跳过名单里的文件本就要跳过，让它们参与 N log N 次 stat 是白付的。
        val known = kept.toHashSet()
        val extras = dir.listFiles { f ->
            f.isFile && f.extension == "json" && f.nameWithoutExtension !in known
        }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        for (file in extras) {
            val id = file.nameWithoutExtension
            val script = readScript(file)
            if (script == null) {
                if (includeBroken) entries.add(brokenEntryOf(id, file))
                continue
            }
            kept.add(id)
            entries.add(entryOf(id, script, file))
        }
        if (kept != indexed) writeIndex(kept)
        entries
    }

    private fun entryOf(id: String, script: Script, file: File): Entry = Entry(
        id = id,
        name = script.name.ifBlank { id },
        stepCount = StepCount.total(script.steps),
        loopTimes = effectiveLoopTimes(id, script),
        modifiedAt = file.lastModified(),
    )

    /**
     * 读不出的脚本：名字只能退回 id（内容不可读），步数按 0 显示并标 [Entry.broken]。
     * 位置留在索引里，因此它出现在列表中原有的位置，而不是末尾。
     */
    private fun brokenEntryOf(id: String, file: File): Entry = Entry(
        id = id,
        name = id,
        stepCount = 0,
        loopTimes = effectiveLoopTimes(id, null),
        modifiedAt = file.lastModified(),
        broken = true,
    )

    fun load(id: String): Script? = synchronized(lock) {
        val script = readScript(fileOf(id)) ?: return null
        script.copy(loopTimes = effectiveLoopTimes(id, script))
    }

    /** 脚本文件是否存在（与「能解析」区分开：损坏的文件存在但读不出来）。 */
    fun exists(id: String): Boolean = synchronized(lock) { fileOf(id).isFile }

    /**
     * 循环次数取值：偏好里有显式记录（旧版本或本次运行写的）就用它，否则用脚本字段。
     * 这样旧版本存过的次数不会因为文件里没有 `lp` 而丢失。
     */
    private fun effectiveLoopTimes(id: String, script: Script?): Int {
        val key = KEY_LOOP + id
        if (prefs.contains(key)) return prefs.getInt(key, Script.DEFAULT_LOOP_TIMES).coerceAtLeast(0)
        return (script?.loopTimes ?: Script.DEFAULT_LOOP_TIMES).coerceAtLeast(0)
    }

    fun setLoopTimes(id: String, times: Int) {
        val value = if (times <= 0) 0 else times
        synchronized(lock) {
            prefs.edit().putInt(KEY_LOOP + id, value).apply()
            // 偏好是读取侧的首选（兼容旧版本只存偏好），文件 lp 必须跟着改，否则两边会分叉
            val script = readScript(fileOf(id)) ?: return
            if (script.loopTimes == value) return
            runCatching { writeScriptFile(id, script.copy(loopTimes = value)) }
        }
    }

    /**
     * 原子写入：先写同目录临时文件，[FileOutputStream.getFD] 刷盘后再 [File.renameTo]。
     *
     * 直接 `writeText` 是「truncate 后逐段写」，进程在写入中途被杀就留下半截文件；
     * 之后 `list()` 会静默剔除该脚本、`load()` 返回 null，用户看到的是脚本凭空消失。
     * 重命名在同一文件系统内是原子的，读者只会看到旧内容或新内容。
     * 刷盘是给掉电兜底：否则 rename 生效但数据还在页缓存里，重启后可能读到 0 字节。
     */
    private fun writeScriptFile(id: String, script: Script) {
        val bytes = script.toJson().toByteArray(Charsets.UTF_8)
        // 落盘字节就是导出的字节，而导入按 [MAX_SCRIPT_BYTES] 判：超过上限的脚本写进去，
        // 它自己也读不回来（实测 3.7 MB 的紧凑文件落盘后涨到 5.2 MB，导出再导入必被拒）。
        // 因此上限卡在这里，写不进去的东西不留成「本应用都打不开的文件」。
        if (bytes.size > MAX_SCRIPT_BYTES) {
            val mb = 1024 * 1024
            throw IllegalStateException(
                appContext.getString(
                    R.string.script_too_large,
                    // 向上取整：4.9 MB 不能显示成「4 MB，上限 4 MB」
                    (bytes.size + mb - 1) / mb,
                    MAX_SCRIPT_BYTES / mb,
                ),
            )
        }
        AtomicFile.write(fileOf(id), bytes)
    }

    var lastPlayedId: String?
        get() = prefs.getString(KEY_LAST_PLAYED, null)
        set(value) {
            // 值没变就不通知：激活同一条脚本、播放前重设同一 id 都不该引起整表刷新
            if (prefs.getString(KEY_LAST_PLAYED, null) == value) return
            prefs.edit().putString(KEY_LAST_PLAYED, value).apply()
            // 「当前脚本」变了：小窗名字、坐标层、列表高亮全都跟 [lastPlayedId] 走，
            // 不通知就会出现「激活了 A，屏幕上仍是 B 的坐标」——拖动还会写进 B。
            notifyChanged()
        }

    /**
     * 没有可播放目标时的回落实例：上次播放（且文件还在）→ 列表首项。
     *
     * 小窗名字与 [com.auto.fairy.PlaybackController.playCurrent] 必须走同一条链：
     * 显示「最近修改」而实际播「列表首项」时，目标被外部删除的那种场景会出现
     * 「界面写着 A、按下去跑的是 B」。
     */
    fun fallbackId(): String? {
        lastPlayedId?.let { id -> if (fileOf(id).isFile) return id }
        return list().firstOrNull()?.id
    }

    // ── 写入 ────────────────────────────────────────────────

    fun save(script: Script, loopTimes: Int? = null): Script = synchronized(lock) {
        val id = script.id.ifBlank { newId() }
        // 循环次数只有一个来源：显式参数优先，其次沿用偏好里的既有值。
        // 这样写出的文件 lp 与偏好永远一致，读取侧的「偏好优先」才不会读到旧值。
        val loops = loopTimes?.let { if (it <= 0) 0 else it } ?: effectiveLoopTimes(id, script)
        val target = script.copy(
            id = id,
            name = script.name.ifBlank { id },
            loopTimes = loops,
        )
        writeScriptFile(id, target)
        prefs.edit().putInt(KEY_LOOP + id, loops).apply()
        val ids = readIndex().toMutableList()
        ids.remove(id)
        ids.add(0, id)
        writeIndex(ids)
        notifyChanged()
        target
    }

    /**
     * [save] 的安全版本：写盘失败（磁盘满 / 目录只读 / 压测时的 IO 异常）返回 null，
     * 由调用方提示用户。
     *
     * 为什么需要它：[save] 会抛 IOException，而它的调用点都在点击回调里——异常会直接
     * 崩进程，用户刚录完的整段脚本连同正在跑的回放一起丢。宁可提示「保存失败」让用户
     * 重试，也不要把「存不下去」变成「整个应用没了」。
     */
    fun saveOrNull(script: Script, loopTimes: Int? = null): Script? =
        runCatching { save(script, loopTimes) }
            .onFailure { android.util.Log.w(TAG, "脚本保存失败: ${script.id}", it) }
            .getOrNull()

    fun delete(id: String): Boolean = synchronized(lock) {
        val removed = fileOf(id).delete()
        val ids = readIndex().toMutableList()
        ids.remove(id)
        writeIndex(ids)
        prefs.edit().remove(KEY_LOOP + id).apply()
        if (lastPlayedId == id) lastPlayedId = null
        notifyChanged()
        removed
    }

    /**
     * 新建：默认「点击屏幕中心 + 等待 1 秒」，保证建完即可直接回放。
     *
     * 写盘失败返回 null，与 [saveOrNull] 同一契约——三个调用点都在点击回调里，
     * 异常抛出去就是「点一下新建，整个应用连同正在跑的回放一起没了」（实测可复现）。
     */
    fun createDefault(name: String, screen: ScreenInfo = ScreenInfo.DEFAULT): Script? {
        val steps = listOf(
            Step.Tap(points = listOf(Point(0.5f, 0.5f)), durationMs = 60L),
            Step.Wait(1_000L),
        )
        return saveOrNull(Script(id = newId(), name = name, screen = screen, steps = steps))
    }

    /** 导入：解析后必须重新分配 id，避免覆盖同 id 的既有脚本（名字归一由 [save] 负责）。 */
    fun import(uri: Uri): Script {
        val text = appContext.contentResolver.openInputStream(uri)?.use(::readCapped)
            ?: throw IllegalStateException("无法读取文件")
        val parsed = Script.fromJson(text)
        return save(parsed.copy(id = newId()))
    }

    /**
     * 读文本，超过 [MAX_SCRIPT_BYTES] 直接拒绝。
     *
     * 一份 13 MB 的合法 JSON（70 万步）解码时把堆压到 192 MB 上限并抛 OutOfMemoryError，
     * 主线程卡住约 5 秒；真正的脚本远小于此。边读边判上限，坏文件不会先撑爆内存。
     */
    private fun readCapped(input: java.io.InputStream): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
            if (buffer.size() > MAX_SCRIPT_BYTES) {
                throw IllegalStateException(
                    appContext.getString(R.string.import_too_large, MAX_SCRIPT_BYTES / 1024 / 1024),
                )
            }
        }
        return buffer.toString("UTF-8")
    }

    fun export(uri: Uri, id: String): Boolean {
        val script = load(id) ?: return false
        appContext.contentResolver.openOutputStream(uri)?.use {
            it.write(script.toJson().toByteArray(Charsets.UTF_8))
        } ?: return false
        return true
    }

    // ── 监听（列表刷新用，替代 LiveData/Flow） ──────────────

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }

    // ── 文件与索引 ──────────────────────────────────────────

    private fun fileOf(id: String) = File(dir, "$id.json")

    /**
     * 读一个脚本文件：不存在或解析失败都返回 null（「缺失」与「损坏」由 list() 区分，
     * 前者从索引剔除、后者保留位置并标记）。
     */
    private fun readScript(file: File): Script? =
        if (file.isFile) runCatching { Script.fromJson(file.readText()) }.getOrNull() else null

    /**
     * 读索引。读失败（权限异常 / 中途被换掉）按空索引处理：
     * `list()` 会把目录里的 json 全部当「未登记文件」接管，顺序信息丢失但脚本不丢；
     * 抛出去则会在刷新列表的主线程上直接崩，代价大得多。
     */
    private fun readIndex(): List<String> = runCatching {
        if (!indexFile.isFile) return@runCatching emptyList()
        indexFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())

    private fun writeIndex(ids: List<String>) {
        // 索引损坏 = 所有脚本一起从列表消失（文件都还在，但顺序信息丢了）。
        // 与脚本正文同一套原子替换，读取侧才不会读到空索引。
        // 写失败不致命：下次 list() 会从目录重新接管，故不让它把界面刷新的调用栈打断。
        runCatching { AtomicFile.write(indexFile, ids.joinToString("\n")) }
    }

    private fun newId(): String {
        val stamp = System.currentTimeMillis().toString(36)
        val tail = (10_000 + (Math.random() * 89_999).toInt()).toString(36)
        return "s_$stamp$tail"
    }

    companion object {
        private const val TAG = "ScriptRepository"
        private const val PREFS = "autotap_scripts"
        private const val KEY_LOOP = "loop_"
        private const val KEY_LAST_PLAYED = "last_played"

        /**
         * 脚本字节上限：4 MB（≈6.3 万步，实测 1000 步 = 66 KB）。
         *
         * 两个用途同一个数，缺一不可：
         * - 导入读取时先判（防 OOM：13 MB 的 70 万步 JSON 能把 192 MB 堆撑爆）；
         * - 落盘/导出时再判（保证本应用写出的任何文件都还能读回来）。
         */
        private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024

        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun formatTime(millis: Long): String = TIME_FORMAT.format(Date(millis))
    }
}

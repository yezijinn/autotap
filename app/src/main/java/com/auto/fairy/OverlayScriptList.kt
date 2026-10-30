package com.auto.fairy

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.auto.fairy.core.runtime.PlaybackEngine
import com.auto.fairy.databinding.OverlayScriptListBinding
import com.auto.fairy.editor.ScriptEditorActivity
import com.auto.fairy.overlay.CoordinateMarkersController
import java.io.File

/**
 * 浮层脚本列表（`TYPE_APPLICATION_OVERLAY`，90%×70%）。
 *
 * 与 [OverlayController] 同级、结构一致：手柄拖动、右上关闭、数据直接读
 * [ScriptRepository]，全程不启动 MainActivity；只有编辑器 / 诊断面板这类
 * 需要 Activity 语义（输入法、返回键、SAF）的界面才走 Activity。
 *
 * 窗口取满屏并铺一层半透明遮罩：窗口外的点击由遮罩接住 = 关闭列表，
 * 同时让返回键有归属（浮层无宿主 Activity，不接管返回键就会关到底层应用）。
 */
object OverlayScriptList {

    private const val DRAG_SLOP_DP = 8f

    /** 坐标层互斥来源标识。 */
    private const val REASON_LIST = "script_list"

    /**
     * 导出文件名最多几个字符（按码点）。40 个汉字/emoji 最多 160 字节，
     * 加 `.json` 仍远低于文件系统的 255 字节上限。
     */
    internal const val FILE_NAME_LIMIT = 40

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var binding: OverlayScriptListBinding? = null
    private var repository: ScriptRepository? = null
    private var adapter: OverlayScriptAdapter? = null
    private var added = false

    private val playbackListener = object : PlaybackController.Listener {
        override fun onPlaybackState(state: PlaybackEngine.State, scriptId: String?) {
            refresh()
        }

        override fun onProgress(cycle: Long) {
            // 行里没有进度文案（只有状态点），巡数变化不需要重读整份脚本列表
        }

        override fun onError(message: String) {}
        override fun onNotice(message: String) {}
    }

    private val repositoryListener: () -> Unit = { refresh() }

    fun open(context: Context) {
        val app = context.applicationContext
        if (added) return
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, R.string.toast_permission_overlay, Toast.LENGTH_LONG).show()
            return
        }
        // 打开列表即暂停：注入点击会落到行内 激活/编辑/删除（见 pauseForModal）
        PlaybackController.pauseForModal()

        val wm = app.getSystemService(WindowManager::class.java)
        val repo = AutoFairyApp.of(app).repository
        // 与 OverlayController 同一套处理：浮层窗口必须显式套主题，?attr 才能解析
        val themed = android.view.ContextThemeWrapper(app, R.style.Theme_AutoFairy)
        val view = OverlayScriptListBinding.inflate(android.view.LayoutInflater.from(themed))
        val screen = ScreenMetrics.size(app)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不设 FLAG_NOT_FOCUSABLE：列表是模态入口，需要接住返回键；
            // 触摸由遮罩独占，不会漏到底层应用。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // 卡片尺寸：宽 90% 屏宽但不超过 overlay_list_max_w，高 70% 屏高
        val maxW = app.resources.getDimensionPixelSize(R.dimen.overlay_list_max_w)
        val cardW = (screen.x * ScreenMetrics.CARD_WIDTH_RATIO).toInt().coerceAtMost(maxW)
        val cardH = (screen.y * ScreenMetrics.CARD_HEIGHT_RATIO).toInt()
        view.listCard.layoutParams = FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER)

        appContext = app
        windowManager = wm
        repository = repo
        binding = view
        adapter = OverlayScriptAdapter(
            onActivate = ::activate,
            onEdit = { entry -> openEditor(entry.id) },
            onMore = ::showRowMenu,
            onBroken = { entry, anchor ->
                toast(R.string.toast_script_broken)
                showRowMenu(entry, anchor)
            },
        )

        view.listRecycler.layoutManager = LinearLayoutManager(app)
        view.listRecycler.adapter = adapter
        // 卡片自身吃掉点击，避免冒泡到遮罩被当成「外部点击」关掉
        view.listCard.setOnClickListener { }
        view.listHandle.setOnTouchListener(cardDragListener())
        view.btnListClose.setOnClickListener { close() }
        view.btnListMore.setOnClickListener { showHeaderMenu(it) }
        view.btnListNew.setOnClickListener { createScript() }
        view.btnListRecord.setOnClickListener { startRecording() }
        // 回放中注入的点击到不了这里：根容器 [InjectShieldLayout] 在 injecting 期间丢弃整串事件
        // （第二十六轮实测：播放中打开加号层、脚本点在其「点击」按钮上，步数 +1；列表同理会命中「激活 / 删除」）。
        // 这层遮罩点击仍按原样：只认用户自己的点击关窗。
        view.root.setOnClickListener { if (!PlaybackController.injecting) close() }
        view.root.isFocusableInTouchMode = true
        view.root.requestFocus()
        view.root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                close()
                true
            } else {
                false
            }
        }

        try {
            wm.addView(view.root, layoutParams)
            added = true
        } catch (t: Throwable) {
            binding = null
            adapter = null
            repository = null
            showToast(app, R.string.toast_overlay_add_failed)
            return
        }

        PlaybackController.addListener(playbackListener)
        repo.addListener(repositoryListener)
        CoordinateMarkersController.setSuppressed(app, REASON_LIST, true)
        // 首屏数据由 addListener 的立即回调刷（见其 KDoc），这里不再重复读一次仓库
    }

    fun close() {
        val view = binding ?: return
        val app = appContext
        PlaybackController.removeListener(playbackListener)
        repository?.removeListener(repositoryListener)
        if (added) runCatching { windowManager?.removeView(view.root) }
        added = false
        binding = null
        adapter = null
        repository = null
        appContext = null
        app?.let { CoordinateMarkersController.setSuppressed(it, REASON_LIST, false) }
    }

    // ── 列表数据 ────────────────────────────────────────────

    private fun refresh() {
        val view = binding ?: return
        val repo = repository ?: return
        // 损坏的脚本也要列出来：整行消失等于用户无从发现、无从删除
        val entries = repo.list(includeBroken = true)
        adapter?.submit(
            list = entries,
            playingId = PlaybackController.playTargetId,
            state = PlaybackController.state,
            hasError = PlaybackController.lastError() != null,
        )
        view.listEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        view.listRecycler.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── 行动作 ──────────────────────────────────────────────

    /**
     * 激活：把脚本设为当前目标并唤出迷你悬浮窗，由用户在小窗上点开始。
     *
     * 刻意不在列表里直接开跑：播放的唯一入口是小悬浮窗的开始键，
     * 避免「列表点一下就开始跑」与「小窗开始」两个入口互相打架。
     */
    private fun activate(entry: ScriptRepository.Entry) {
        val app = appContext ?: return
        val repo = repository ?: return
        // 先关列表再改目标：close 会摘掉仓库监听，否则这次写入白触发一次全表刷新
        close()
        repo.lastPlayedId = entry.id
        if (OverlayController.show(app)) toast(R.string.toast_script_activated)
    }

    private fun showRowMenu(entry: ScriptRepository.Entry, anchor: View) {
        val root = binding?.root ?: return
        // 损坏的行没有可执行动作：激活/编辑/导出全都要先读文件，只有删除有意义
        val items = if (entry.broken) {
            listOf(
                context().getString(R.string.menu_delete) to { confirmDelete(entry, anchor) },
            )
        } else {
            listOf(
                context().getString(R.string.menu_activate) to { activate(entry) },
                context().getString(R.string.menu_edit) to { openEditor(entry.id) },
                context().getString(R.string.menu_export) to { export(entry) },
                context().getString(R.string.menu_delete) to { confirmDelete(entry, anchor) },
            )
        }
        PopupMenus.show(root, anchor, items)
    }

    private fun confirmDelete(entry: ScriptRepository.Entry, anchor: View) {
        val root = binding?.root ?: return
        val repo = repository ?: return
        PopupMenus.show(
            root,
            anchor,
            listOf(
                context().getString(R.string.dialog_delete_message, entry.name) to {
                    if (PlaybackController.playingScriptId == entry.id) PlaybackController.stop()
                    repo.delete(entry.id)
                    toast(R.string.toast_script_deleted)
                },
                context().getString(R.string.dialog_cancel) to { },
            ),
        )
    }

    /**
     * 浮层没有 Activity，拿不到 SAF 的选择器，导出固定写到应用外部目录，
     * 路径随提示给出，用户可直接在文件管理器里取。
     */
    private fun export(entry: ScriptRepository.Entry) {
        val app = appContext ?: return
        val repo = repository ?: return
        val script = repo.load(entry.id) ?: return
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "autotap")
        // 名字当文件名：先压成一行再按码点截断，否则换行会进文件名、
        // 长中文名（85 字以上）会超 255 字节，导出变成一个没有出路的失败。
        // 尾部带脚本 id 后 4 位：截断后同名的两条脚本不会互相覆写。
        val safeName = OverlayController.oneLine(entry.name, FILE_NAME_LIMIT)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val file = File(dir, "$safeName-${entry.id.takeLast(4)}.json")
        val ok = runCatching {
            dir.mkdirs()
            // 与脚本正文同一套原子替换：导出途中被杀不会留下半截文件
            AtomicFile.write(file, script.toJson())
        }.isSuccess
        val message = if (ok) {
            app.getString(R.string.toast_export_ok) + " · " + file.absolutePath
        } else {
            app.getString(R.string.toast_export_failed)
        }
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }

    // ── 底部操作条 ──────────────────────────────────────────

    /**
     * 新建：先落一个带默认步骤的脚本，再交给浮层编辑器就地改名。
     * 浮层里没有宿主 Activity，弹不出输入框，改名放进编辑器的顶部栏反而更顺。
     */
    private fun createScript() {
        val app = appContext ?: return
        val repo = repository ?: return
        // 编号带上损坏条目，避免与列表里已有的名字重复
        val name = app.getString(R.string.dialog_default_script_name, repo.list(includeBroken = true).size + 1)
        val script = repo.createDefault(name, ScreenMetrics.screenInfo(app))
        if (script == null) {
            toast(R.string.toast_save_failed)
            return
        }
        openEditor(script.id)
    }

    private fun startRecording() {
        val app = appContext ?: return
        if (!Settings.canDrawOverlays(app)) {
            toast(R.string.toast_recording_needs_overlay)
            return
        }
        // 录制独占触摸：先关列表；迷你条由 RecordOverlay 自己收起
        close()
        val started = RecordOverlay.start(app) { }
        if (!started) toast(R.string.toast_permission_overlay)
    }

    private fun openEditor(scriptId: String) {
        val app = appContext ?: return
        close()
        val intent = ScriptEditorActivity.intent(app, scriptId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    private fun showHeaderMenu(anchor: View) {
        val root = binding?.root ?: return
        PopupMenus.show(
            root,
            anchor,
            listOf(
                context().getString(R.string.overlay_diagnose) to {
                    openPanel(PanelActivity.PANEL_DIAGNOSE)
                },
                context().getString(R.string.overlay_keepalive) to {
                    openPanel(PanelActivity.PANEL_KEEPALIVE)
                },
                context().getString(R.string.overlay_settings) to {
                    openPanel(PanelActivity.PANEL_SETTINGS)
                },
            ),
        )
    }

    /**
     * 诊断/保活/设置也是 90%×70% 的浮层卡片，与列表同尺寸；浮层窗口恒在 Activity 之上，
     * 不先关列表就会把卡片整个盖住，所以这里先关列表再开面板。
     */
    private fun openPanel(panel: String) {
        val app = appContext ?: return
        close()
        PanelActivity.open(app, panel)
    }

    // ── 拖动 ────────────────────────────────────────────────

    /** 窗口固定满屏，拖动的是卡片：只改 translation，不重排窗口。 */
    private fun cardDragListener(): View.OnTouchListener {
        val view = binding?.root
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        var slop = 0f

        return View.OnTouchListener { _, event ->
            val card = binding?.listCard ?: return@OnTouchListener false
            if (slop == 0f) slop = DRAG_SLOP_DP * card.resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTx = card.translationX
                    startTy = card.translationY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && kotlin.math.abs(dx) + kotlin.math.abs(dy) > slop) {
                        dragging = true
                    }
                    if (dragging && view != null) {
                        val maxX = ((view.width - card.width) / 2f).coerceAtLeast(0f)
                        val maxY = ((view.height - card.height) / 2f).coerceAtLeast(0f)
                        card.translationX = (startTx + dx).coerceIn(-maxX, maxX)
                        card.translationY = (startTy + dy).coerceIn(-maxY, maxY)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    // ── 工具 ────────────────────────────────────────────────

    private fun context(): Context = binding?.root?.context ?: appContext
        ?: throw IllegalStateException("悬浮列表未显示")

    private fun toast(resId: Int) = showToast(appContext, resId)
}

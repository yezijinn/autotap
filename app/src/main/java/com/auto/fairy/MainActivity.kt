package com.auto.fairy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.model.Script
import com.auto.fairy.databinding.ActivityMainBinding
import com.auto.fairy.overlay.CoordinateMarkersController

/**
 * 单 Activity + Fragment 容器。
 *
 * 顶部栏四个入口：脚本列表 / 诊断 / 保活 / 设置。后三个默认开浮层卡片
 * （[PanelActivity] 里的 [DiagnoseFragment] / [KeepAliveFragment] / [SettingsFragment]），
 * 长按才回到全屏页——浮层不可用时仍有退路。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ScriptRepository

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val script = repository.import(uri)
            toast(getString(R.string.toast_import_ok, script.name))
        } catch (t: Throwable) {
            toast(getString(R.string.toast_import_failed, t.message ?: "未知错误"))
        }
    }

    /**
     * 后台提示要能看见，就得有通知权限（targetSdk 34 起「后台 + 未授权」的吐司被系统静默丢弃）。
     * 本应用不是通知类应用，所以只问一次；拒绝也不再有二次弹窗，诊断页仍留着记录。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (AppSettings.hasAskedNotificationPermission(this)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        AppSettings.markNotificationPermissionAsked(this)
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = AutoFairyApp.of(this).repository

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, ScriptListFragment(), TAG_SCRIPTS)
                .commit()
        }
        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }
        updateTitle()
        askNotificationPermissionOnce()

        binding.btnOverlayEntry.setOnClickListener { openOverlayList() }
        // 三个入口默认开浮层卡片；长按保留原全屏页作为兜底（浮层窗口不可用时的退路）
        binding.btnDiagnose.setOnClickListener {
            PanelActivity.open(this, PanelActivity.PANEL_DIAGNOSE)
        }
        binding.btnDiagnose.setOnLongClickListener {
            openFragment(TAG_DIAGNOSE) { DiagnoseFragment() }
            true
        }
        binding.btnKeepalive.setOnClickListener {
            PanelActivity.open(this, PanelActivity.PANEL_KEEPALIVE)
        }
        binding.btnKeepalive.setOnLongClickListener {
            openFragment(TAG_KEEPALIVE) { KeepAliveFragment() }
            true
        }
        binding.btnSettings.setOnClickListener {
            PanelActivity.open(this, PanelActivity.PANEL_SETTINGS)
        }
        binding.btnSettings.setOnLongClickListener {
            openPlaceholder(TAG_SETTINGS, R.string.nav_title_settings, R.string.placeholder_settings)
            true
        }
        binding.fabNewScript.setOnClickListener { showFabMenu(it) }
        binding.btnFixPermission.setOnClickListener { fixPermissions() }
        binding.bannerPermissions.setOnClickListener { fixPermissions() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        // 悬浮窗权限可能在进程启动之后才授予，坐标层也可能被系统摘掉：回到前台补挂一次。
        // 没有激活会话时 restore 立即返回，冷启动到这里就是空转，不会凭空画出红点。
        // 真有会话要重建也不能压在首帧关键路径上：实测首帧前的这次窗口事务把这一帧
        // 推后 170~260ms（同步开销只有 10~30ms，见更新日志 2026-10-25「首帧前少一次重建」）。
        // 用户看不见「坐标层晚了一点」——那一帧还没画出来，圆点本来也不在屏上。
        Choreographer.getInstance().postFrameCallback {
            binding.root.post { CoordinateMarkersController.restore(this) }
        }
    }

    override fun onStart() {
        super.onStart()
        // 无障碍服务连接/断开会回调，横幅不必等下一次 onResume
        PermissionStatus.addListener(permissionListener)
        refreshPermissionBanner()
    }

    override fun onStop() {
        PermissionStatus.removeListener(permissionListener)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * 回放中**注入的**点击打不到本页：只丢弃，不暂停。
     *
     * 与 [PanelActivity]、[ScriptEditorActivity]、[InjectShieldLayout]、对话框差一步：
     * 那几处丢完还要 [PlaybackController.pauseForModal]（一开模态用户就该停手，
     * 顺带覆盖音量键恢复）。主页不是模态——它正是「按下开始、还没切到目标应用」的那一屏，
     * 跟着暂停会把正常流程打断：实测（第二十七轮）主页上按 ▶，脚本首击 100ms 就落在本页，
     * 一暂停就变「已暂停」，脚本等于没跑起来（两次不同脚本各复现 1 次）。
     *
     * 所以判据按事件来源分：注入的（`deviceId < 0`）吞掉，内核真触摸（`deviceId` 是 InputDevice
     * id，本机为 3）照常放行。第二十八轮实测：无障碍注入、`input tap`、`input motionevent`
     * 全是 −1；`sendevent` 与 MuMu 宿主鼠标点击都是 3，都能通过闸门。
     * 失败方向可接受：某些 ROM 若给注入事件正 deviceId，闸门退化成「没有它」，
     * 和加它之前一样，不会把用户的手指一起挡掉。
     *
     * 别把这句读成「播放中手指一定点得动」：引擎每 160ms 一笔注入，在途手指会被系统取消
     * （DOWN 后 13~145ms 收到 ACTION_CANCEL），与闸门无关，应用侧没有解法。第二十八轮
     * 实测「编辑」：30ms 轻点 11/15 成功，108ms 正常点击 0/15——间隔越短越容易点成，
     * 但不是单调关系（90ms 周期的脚本上 30ms 轻点 15/15 全成）。
     * 本页卡片四键走 `setOnClickListener`，缺 UP 就不成立；迷你条挂在 DOWN 上，
     * 按压 30/110/230ms 三档实测停止均 10/10，不受影响。
     *
     * 要挡的是实测到的这条：播放中回主页，注入点击能命中卡片的 激活/编辑/导出/删除、
     * FAB 与顶栏四个 tab（逐点命中）；其中「导出」会把系统「保存到」选择器
     * 拉到仍在运行的回放前面——那个窗口不是我们的，没有闸门可加。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (PlaybackController.injecting && ev.deviceId < 0) return true
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("AppCompat 仍走此入口；先关弹层再交给默认返回逻辑")
    override fun onBackPressed() {
        if (PopupMenus.isShowing(this)) {
            PopupMenus.hide(this)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ── 浮层入口（M5） ──────────────────────────────────────

    /**
     * 打开浮层脚本列表。MainActivity 已降级为「引导 / 权限 / 入口」，
     * 常规播放、编辑、录制都在浮层里完成，不再回到这个全屏页。
     */
    private fun openOverlayList() {
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.toast_permission_overlay)
            openOverlaySettings()
            return
        }
        OverlayScriptList.open(this)
    }

    // ── 导航 ────────────────────────────────────────────────

    private fun openPlaceholder(tag: String, titleRes: Int, hintRes: Int) {
        openFragment(tag) { PlaceholderFragment.newInstance(titleRes, hintRes) }
    }

    private fun openFragment(tag: String, create: () -> Fragment) {
        val current = supportFragmentManager.findFragmentById(R.id.container)
        if (current?.tag == tag) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, create(), tag)
            .addToBackStack(tag)
            .commit()
    }

    private fun updateTitle() {
        val tag = supportFragmentManager.findFragmentById(R.id.container)?.tag
        val title = when (tag) {
            TAG_DIAGNOSE -> R.string.nav_title_diagnose
            TAG_KEEPALIVE -> R.string.nav_title_keepalive
            TAG_SETTINGS -> R.string.nav_title_settings
            else -> R.string.nav_title_scripts
        }
        binding.textAppbarTitle.setText(title)
        // 只有脚本列表需要新建按钮
        val showFab = tag == null || tag == TAG_SCRIPTS
        binding.fabNewScript.visibility = if (showFab) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ── FAB 菜单 ────────────────────────────────────────────

    private fun showFabMenu(anchor: android.view.View) {
        PopupMenus.show(
            this,
            anchor,
            listOf(
                getString(R.string.fab_new_script) to { askNewScriptName() },
                getString(R.string.fab_record_script) to { startRecording() },
                getString(R.string.fab_import_script) to { importLauncher.launch(arrayOf("*/*")) },
            ),
        )
    }

    private fun askNewScriptName() {
        // 打开命名框即暂停：注入点击会点到「确定」（立刻建一个空脚本）
        PlaybackController.pauseForModal()
        val defaultName = getString(R.string.dialog_default_script_name, repository.list().size + 1)
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_new_script_hint)
            setText(defaultName)
            setSelectAllOnFocus(true)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_new_script_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim().ifBlank { defaultName }
                val script = repository.createDefault(name, ScreenMetrics.screenInfo(this))
                if (script == null) {
                    toast(R.string.toast_save_failed)
                } else {
                    toast(getString(R.string.toast_script_created, script.name))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnDismissListener {
            CoordinateMarkersController.setSuppressed(this, REASON_DIALOG, false)
        }
        // 窗口外一击不算取消（回放中那是注入的点击），只认「取消」与返回键；空闲时照旧
        dialog.setCanceledOnTouchOutside(!PlaybackController.injecting)
        CoordinateMarkersController.setSuppressed(this, REASON_DIALOG, true)
        dialog.show()
        // 命名框是独立窗口：按钮（「确定」= 立刻建一条空脚本）自己也要挡注入
        dialog.shieldAgainstInjection()
    }

    private fun startRecording() {
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.toast_recording_needs_overlay)
            openOverlaySettings()
            return
        }
        // 上次录制被进程杀掉时会留下草稿：先给恢复入口，不直接开录把它覆盖掉
        val draft = RecordDraft.load(filesDir)
        if (draft != null) askRecoverDraft(draft) else beginRecording()
    }

    private fun beginRecording() {
        // 录制独占触摸：列表浮层必须先关（M5 §3.3 互斥表）
        OverlayScriptList.close()
        val started = RecordOverlay.start(this) { _ ->
            // 保存后仓库会通知列表刷新；这里无需额外动作
        }
        if (!started) toast(R.string.toast_permission_overlay)
    }

    /** 上次录制被中断：给一次恢复入口，恢复成正常脚本；放弃则连同草稿一起清掉再开录。 */
    private fun askRecoverDraft(draft: Script) {
        // 打开草稿恢复框即暂停：注入点击会点到「放弃草稿」或「恢复」
        PlaybackController.pauseForModal()
        CoordinateMarkersController.setSuppressed(this, REASON_DIALOG, true)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.draft_recover_title)
            .setMessage(getString(R.string.draft_recover_message, StepCount.total(draft.steps)))
            .setPositiveButton(R.string.draft_recover_ok) { _, _ ->
                // 与录制保存同一道关：编译不过的草稿不写进仓库
                val ok = runCatching { ScriptCompiler.compile(draft) }.isSuccess
                val saved = if (ok) repository.saveOrNull(draft.copy(id = "")) else null
                if (saved == null) {
                    toast(R.string.toast_save_failed)
                } else {
                    RecordDraft.clear(filesDir)
                    toast(getString(R.string.toast_record_saved, saved.name))
                }
            }
            .setNegativeButton(R.string.draft_recover_discard) { _, _ ->
                RecordDraft.clear(filesDir)
                beginRecording()
            }
            .create()
        dialog.setOnDismissListener {
            CoordinateMarkersController.setSuppressed(this, REASON_DIALOG, false)
        }
        // 窗口外一击不算取消（回放中那是注入的点击），只认两个按钮与返回键；空闲时照旧
        dialog.setCanceledOnTouchOutside(!PlaybackController.injecting)
        dialog.show()
        // 两个按钮都会动到草稿（写进仓库 / 清掉）：窗口级挡注入
        dialog.shieldAgainstInjection()
    }

    // ── 权限 ────────────────────────────────────────────────

    private fun isA11yEnabled(): Boolean = PermissionStatus.isA11yEnabled(this)

    private fun isA11yConnected(): Boolean = PermissionStatus.isA11yConnected(this)

    private val permissionListener: () -> Unit = { refreshPermissionBanner() }

    /**
     * 横幅只说实话：开关开着但服务没连上时，说「未连接」而不是「未开启」。
     * 后者把用户支到设置页去关一个本来就开着的开关（真机实测被这么误导过）。
     */
    private fun refreshPermissionBanner() {
        val a11y = isA11yEnabled()
        val connected = isA11yConnected()
        val overlay = Settings.canDrawOverlays(this)
        when {
            !a11y && !overlay -> {
                binding.bannerPermissions.visibility = android.view.View.VISIBLE
                binding.textPermissionHint.setText(R.string.permission_hint_both)
            }

            !a11y -> {
                binding.bannerPermissions.visibility = android.view.View.VISIBLE
                binding.textPermissionHint.setText(R.string.permission_hint_a11y)
            }

            !connected -> {
                binding.bannerPermissions.visibility = android.view.View.VISIBLE
                binding.textPermissionHint.setText(R.string.permission_hint_a11y_disconnected)
            }

            !overlay -> {
                binding.bannerPermissions.visibility = android.view.View.VISIBLE
                binding.textPermissionHint.setText(R.string.permission_hint_overlay)
            }

            else -> binding.bannerPermissions.visibility = android.view.View.GONE
        }
    }

    private fun fixPermissions() {
        when {
            // 未连接就把人送去无障碍设置：开关关着与「开着但没连上」都是这一条
            // （isA11yConnected 恒蕴含 isA11yEnabled，不必再判后者）
            !isA11yConnected() -> {
                toast(R.string.toast_permission_a11y)
                runCatching {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }

            !Settings.canDrawOverlays(this) -> {
                toast(R.string.toast_permission_overlay)
                openOverlaySettings()
            }
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
    }

    // ── 工具 ────────────────────────────────────────────────

    private fun toast(resId: Int) = showToast(this, resId)

    private fun toast(text: String) = showToast(this, text)

    companion object {
        private const val TAG_SCRIPTS = "scripts"
        private const val TAG_DIAGNOSE = "diagnose"
        private const val TAG_KEEPALIVE = "keepalive"
        private const val TAG_SETTINGS = "settings"

        /** 坐标层互斥来源标识（本页弹窗）。 */
        private const val REASON_DIALOG = "dialog"
    }
}

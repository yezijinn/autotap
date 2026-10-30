package com.auto.fairy.editor

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.auto.fairy.AutoFairyApp
import com.auto.fairy.OverlayController
import com.auto.fairy.PlaybackController
import com.auto.fairy.R
import com.auto.fairy.ScreenMetrics
import com.auto.fairy.ScriptRepository
import com.auto.fairy.showToast
import com.auto.fairy.shieldAgainstInjection
import com.auto.fairy.shieldModalTouch
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import com.auto.fairy.databinding.ActivityScriptEditorBinding
import com.auto.fairy.databinding.DialogAddStepBinding
import com.auto.fairy.overlay.CoordinateMarkersController

/**
 * 脚本编辑器。
 *
 * 独立 Activity：与主页 Fragment 返回栈、录制/播放浮层的生命周期互不干扰。
 * 编辑内容全部先在内存里改，点「保存」才写回仓库；返回时有未保存修改会二次确认。
 * 坐标只以归一化值保存，像素仅用于显示（见 [CoordDisplay]）。
 */
class ScriptEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var repository: ScriptRepository
    private lateinit var stepAdapter: StepAdapter
    private lateinit var headerAdapter: EditorHeaderAdapter
    private lateinit var emptyAdapter: EditorEmptyAdapter

    private var script: Script? = null
    private val steps = ArrayList<Step>()
    private var loopTimes = 0
    private var defaultDelayMs = Script.DEFAULT_ACTION_DELAY_MS
    private var originalName = ""
    private var originalSteps: List<Step> = emptyList()
    private var originalLoopTimes = 0
    private var originalDefaultDelayMs = Script.DEFAULT_ACTION_DELAY_MS

    private var picker: CoordinatePicker? = null
    private var addStepDialog: Dialog? = null

    /** 显示用屏幕尺寸：当前设备的分辨率（注入坐标系就是它）。 */
    private val screen: Pair<Int, Int>
        get() = CoordDisplay.screenFor(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFloatingWindow()
        repository = AutoFairyApp.of(this).repository

        // 编辑器全屏吃触摸，播放控制条先让位；关闭时由 hide/show 自然恢复
        OverlayController.hidePanel()
        // 打开编辑器即暂停：注入点击会落到保存/删除/上下移这些会改脚本正文的按钮上
        PlaybackController.pauseForModal()
        // 坐标标记层同样让位：编辑时满屏红点会挡住步骤列表
        CoordinateMarkersController.setSuppressed(this, REASON_EDITOR, true)

        val current = intent.getStringExtra(EXTRA_SCRIPT_ID)
            ?.let { repository.load(it) }
            ?: repository.createDefault(
                getString(R.string.editor_name_hint),
                ScreenMetrics.screenInfo(this),
            )
            ?: run {
                toast(R.string.toast_save_failed)
                finish()
                return
            }
        script = current
        steps.addAll(current.steps)
        loopTimes = current.loopTimes
        defaultDelayMs = current.defaultActionDelayMs
        originalName = current.name
        originalSteps = ArrayList(current.steps)
        originalLoopTimes = loopTimes
        originalDefaultDelayMs = defaultDelayMs

        setupBar()
        setupSteps()
        render()
    }

    override fun onDestroy() {
        picker?.cancel()
        picker = null
        addStepDialog?.dismiss()
        // 编辑器关闭：播放控制条恢复常驻（onCreate 里 hidePanel 的逆操作）
        OverlayController.showBall()
        CoordinateMarkersController.setSuppressed(this, REASON_EDITOR, false)
        // 回放是被本编辑器暂停的：不自动恢复，至少要告诉用户它还在暂停
        PlaybackController.noticeStillPausedAfterModal(this)
        super.onDestroy()
    }

    // ── 顶部栏 ──────────────────────────────────────────────

    /**
     * 浮层化窗口：宽 90% 屏宽、高 70% 屏高，圆角卡片。
     * 尺寸只能给窗口（`activity_script_editor.xml` 保持原样复用），
     * 内部 RecyclerView 自滚动，步骤再多也不撑破浮层。
     */
    private fun setupFloatingWindow() {
        val screen = ScreenMetrics.size(this)
        window?.setLayout(
            (screen.x * ScreenMetrics.CARD_WIDTH_RATIO).toInt(),
            (screen.y * ScreenMetrics.CARD_HEIGHT_RATIO).toInt(),
        )
        window?.setDimAmount(0.4f)
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        binding.root.background = ContextCompat.getDrawable(this, R.drawable.bg_dialog_card)
        binding.root.clipToOutline = true
    }

    private fun setupBar() {
        binding.textEditorName.setText(originalName)
        binding.textEditorName.setSelection(originalName.length)
        binding.btnEditorBack.setOnClickListener { leave() }
        binding.btnEditorSave.setOnClickListener { save() }
        binding.fabAddStep.setOnClickListener { showAddStep() }
    }

    // ── 步骤列表 ────────────────────────────────────────────

    private fun setupSteps() {
        stepAdapter = StepAdapter(
            onEdit = ::editStep,
            onDelete = ::deleteStep,
            onMoveUp = { moveStep(it, -1) },
            onMoveDown = { moveStep(it, 1) },
            screen = { screen },
        )
        headerAdapter = EditorHeaderAdapter(
            onLoopTimesChanged = { loopTimes = it },
            onDefaultDelayChanged = { defaultDelayMs = it },
        )
        emptyAdapter = EditorEmptyAdapter()
        binding.recyclerSteps.layoutManager = LinearLayoutManager(this)
        binding.recyclerSteps.adapter = ConcatAdapter(headerAdapter, stepAdapter, emptyAdapter)
    }

    private fun render() {
        headerAdapter.submit(
            loopTimes,
            defaultDelayMs,
            screen,
            CoordDisplay.recordedFor(this, script),
        )
        stepAdapter.submit(steps)
        emptyAdapter.setVisible(steps.isEmpty())
    }

    private fun editStep(index: Int) {
        val step = steps.getOrNull(index) ?: return
        if (step is Step.Multi) {
            // 多指轨迹目前只有录制/导入能产生，编辑入口留到后续版本
            toast(R.string.toast_step_unsupported)
            return
        }
        StepEditDialog(
            activity = this,
            screen = screen,
            initial = step,
            kind = StepKind.of(step),
            bodyPrefix = "${index + 1}",
            defaultDelayMs = defaultDelayMs,
        ) { result ->
            if (result != null && index in steps.indices) {
                steps[index] = result
                render()
            }
        }.show()
    }

    private fun deleteStep(index: Int) {
        if (index !in steps.indices) return
        steps.removeAt(index)
        render()
    }

    private fun moveStep(index: Int, delta: Int) {
        val target = index + delta
        if (index !in steps.indices || target !in steps.indices) return
        val moved = steps.removeAt(index)
        steps.add(target, moved)
        render()
    }

    private fun addStep(kind: StepKind) {
        StepEditDialog(
            activity = this,
            screen = screen,
            initial = StepEditDialog.defaultStep(kind),
            kind = kind,
            bodyPrefix = "${steps.size + 1}",
            defaultDelayMs = defaultDelayMs,
        ) { result ->
            if (result != null) {
                steps.add(result)
                render()
            }
        }.show()
    }

    /** 底部类型选择面板：自绘 Dialog，不引入 Material BottomSheet。 */
    private fun showAddStep() {
        // 打开类型选择即暂停：注入点击会自己选一个类型并把空步骤加进列表
        PlaybackController.pauseForModal()
        val dialog = Dialog(this, R.style.EditorSheetDialog)
        val view = DialogAddStepBinding.inflate(layoutInflater)
        dialog.setContentView(view.root)
        dialog.setOnDismissListener { addStepDialog = null }

        // 回放中注入的点击打到的是同一个遮罩：不拦的话用户刚打开的类型选择会被脚本点掉，
        // 随后的点击落到编辑器步骤行上（与浮层同一处理，见 PlaybackController.injecting）
        view.addStepScrim.setOnClickListener { if (!PlaybackController.injecting) dialog.dismiss() }
        view.itemAddTap.setOnClickListener {
            dialog.dismiss()
            addStep(StepKind.TAP)
        }
        view.itemAddSwipe.setOnClickListener {
            dialog.dismiss()
            addStep(StepKind.SWIPE)
        }
        view.itemAddWait.setOnClickListener {
            dialog.dismiss()
            addStep(StepKind.WAIT)
        }
        view.itemAddLoop.setOnClickListener {
            dialog.dismiss()
            addStep(StepKind.LOOP)
        }

        addStepDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        // 四个「添加 X」都是即时动作（选完就弹步骤框）：窗口级挡注入，
        // 否则片边界前落下的注入点击会连着弹两层框
        dialog.shieldAgainstInjection()
    }

    // ── 保存 / 退出 ─────────────────────────────────────────

    private val isDirty: Boolean
        get() = binding.textEditorName.text.toString().trim() != originalName ||
            steps != originalSteps ||
            loopTimes != originalLoopTimes ||
            defaultDelayMs != originalDefaultDelayMs

    private fun save() {
        val current = script ?: return
        val name = binding.textEditorName.text.toString().trim()
            .ifBlank { getString(R.string.editor_name_hint) }
        // 写盘失败不能崩进程：留在编辑器里让用户重试，改动一个字都不丢
        val saved = repository.saveOrNull(
            current.copy(
                name = name,
                steps = ArrayList(steps),
                defaultActionDelayMs = defaultDelayMs,
            ),
            loopTimes,
        ) ?: run {
            toast(R.string.toast_save_failed)
            return
        }
        script = saved
        originalName = saved.name
        originalSteps = ArrayList(steps)
        originalLoopTimes = loopTimes
        originalDefaultDelayMs = saved.defaultActionDelayMs
        binding.textEditorName.setText(saved.name)
        toast(R.string.toast_saved)
        setResult(RESULT_OK)
        finish()
    }

    private fun leave() {
        when {
            picker?.isShowing() == true -> {
                picker?.cancel()
                picker = null
            }

            isDirty -> confirmDiscard()
            else -> finish()
        }
    }

    private fun confirmDiscard() {
        // 打开确认框即暂停：注入点击会点到「放弃修改」（未保存的编辑直接丢）
        PlaybackController.pauseForModal()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_discard_title)
            .setMessage(R.string.dialog_discard_message)
            .setPositiveButton(R.string.dialog_discard_exit) { _, _ -> finish() }
            .setNegativeButton(R.string.editor_cancel, null)
            .create()
        // 窗口外一击不算取消：回放中那是注入的点击，随后会落到步骤行的 ✗ / ↑↓ 上；
        // 空闲时保持原样（点外部收起）。
        dialog.setCanceledOnTouchOutside(!PlaybackController.injecting)
        dialog.show()
        // 「放弃」会直接退出编辑器、丢掉未保存的改动：窗口级挡注入
        dialog.shieldAgainstInjection()
    }

    @Deprecated("AppCompat 仍走此入口；与顶部返回键同一路径")
    override fun onBackPressed() = leave()

    /**
     * 打开编辑器后的头一片注入点击一律丢弃（同 [PanelActivity.dispatchTouchEvent]）：
     * 那一片还能点到「保存」「✗ 删步骤」「↑↓ 移动」——改的是脚本正文。
     * 注入停下后自动恢复；返回键不走这条路径。编辑器里按音量键恢复播放时，
     * 再来的一击重新暂停并吞掉，否则整窗只读、连保存都点不动。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (shieldModalTouch(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun toast(resId: Int) = showToast(this, resId)

    companion object {
        private const val EXTRA_SCRIPT_ID = "script_id"

        /** 坐标层互斥来源标识。 */
        private const val REASON_EDITOR = "editor"

        fun intent(context: Context, scriptId: String): Intent =
            Intent(context, ScriptEditorActivity::class.java).putExtra(EXTRA_SCRIPT_ID, scriptId)
    }
}

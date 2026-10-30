package com.auto.fairy.editor

import android.app.Activity
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.auto.fairy.PlaybackController
import com.auto.fairy.R
import com.auto.fairy.shieldAgainstInjection
import com.auto.fairy.showToast
import com.auto.fairy.core.model.Point
import com.auto.fairy.core.model.Step
import com.auto.fairy.databinding.BlockCoordPickerBinding
import com.auto.fairy.databinding.BlockNumberBinding
import com.auto.fairy.databinding.DialogStepEditBinding

/**
 * 步骤编辑对话框：一个容器按类型切换区块，避免四套布局。
 *
 * 坐标永远以归一化 [Point] 保存，界面上的像素行只是按屏幕尺寸反算的显示值；
 * 「取坐标」会拉起全屏浮层，确认后回填到对应区块。
 *
 * 点击/滑动自带两项属性：动作延迟（运行前等待；留空表示跟随脚本的
 * defaultActionDelayMs，提示里给出当前值）与「本步循环」次数；等待步骤只有时长，
 * 不再带动作延迟。
 */
class StepEditDialog(
    private val activity: Activity,
    private val screen: Pair<Int, Int>,
    initial: Step?,
    private val kind: StepKind,
    private val bodyPrefix: String = "",
    /** 脚本级默认动作延迟：只用于提示「留空跟随多少」，绝不写进步骤。 */
    private val defaultDelayMs: Long,
    private val onResult: (Step?) -> Unit,
) {

    private val binding = DialogStepEditBinding.inflate(activity.layoutInflater)
    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setView(binding.root)
        .setNegativeButton(R.string.editor_cancel, null)
        .setPositiveButton(R.string.editor_confirm, null)
        .create()

    private var tapPoint = Point(0.5f, 0.5f)
    private var swipeFrom = Point(0.5f, 0.75f)
    private var swipeTo = Point(0.5f, 0.25f)
    private var loopTimes = 0
    /** bind 期间写入输入框不应被当成用户编辑。 */
    private var mutating = false
    private val loopBody = ArrayList<Step>()
    private var loopAdapter: StepAdapter? = null
    private var picker: CoordinatePicker? = null

    init {
        bindTitle()
        bindBlocks()
        bindBody()
        bindPickButtons()
        applyInitial(initial)
        renderCoords()
    }

    fun show() {
        // 打开步骤编辑即暂停：注入点击会落到「确定」（把改了一半的数值写回步骤）
        PlaybackController.pauseForModal()
        // 窗口外一击不算取消：它可能是回放中注入的点击，落在步骤行上改动列表；
        // 即便对真实用户，填了一半的对话框一点也不该丢掉内容。只认「取消」与返回键。
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val step = buildStep() ?: return@setOnClickListener
                onResult(step)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            // 对话框关掉时若浮层还开着，一并撤掉，避免留下无主窗口
            picker?.cancel()
            picker = null
        }
        dialog.show()
        // 「确定」会把改了一半的数值写回步骤：窗口级挡注入（与上面「点外部不取消」同一不变式）
        dialog.shieldAgainstInjection()
    }

    // ── 装配 ────────────────────────────────────────────────

    private fun bindTitle() {
        binding.textEditTitle.setText(kind.titleRes)
    }

    private fun bindBlocks() {
        binding.blockTap.visibility = visibleIf(kind == StepKind.TAP)
        binding.blockSwipe.visibility = visibleIf(kind == StepKind.SWIPE)
        binding.blockWait.visibility = visibleIf(kind == StepKind.WAIT)
        binding.blockLoop.visibility = visibleIf(kind == StepKind.LOOP)

        // 点击的时长是「按下到松开」，滑动的是「滑动时长」，两者不是同一概念，标签分开写
        binding.tapDuration.textDurationLabel.setText(R.string.editor_duration)
        binding.swipeDuration.textDurationLabel.setText(R.string.editor_swipe_duration)
        binding.waitDuration.textDurationLabel.setText(R.string.editor_duration)

        bindActionDelayBlock(binding.tapPreDelay)
        bindRepeatBlock(binding.tapRepeat)
        bindActionDelayBlock(binding.swipePreDelay)
        bindRepeatBlock(binding.swipeRepeat)
    }

    /**
     * 动作延迟块：空框表示「跟随脚本默认」，用输入框自身的 placeholder 标出来。
     *
     * 数字键盘打不出「默认」二字，但 placeholder 只是提示、进不了文本，
     * 因此既不会污染解析，也不会让空框看起来像「没填」的坏状态
     * （步骤卡片副行写的就是「默认」，两处口径要一致）。
     */
    private fun bindActionDelayBlock(block: BlockNumberBinding) {
        block.textNumberLabel.setText(R.string.editor_action_delay)
        block.textNumberHint.text =
            activity.getString(R.string.editor_action_delay_hint, defaultDelayMs)
        block.editNumber.hint = activity.getString(R.string.step_delay_default)
    }

    private fun bindRepeatBlock(block: BlockNumberBinding) {
        block.textNumberLabel.setText(R.string.editor_step_repeat)
        block.textNumberHint.setText(R.string.editor_step_repeat_hint)
        // 复用同一个数字输入块：循环次数永远有值，别把「默认」的 placeholder 带过来
        block.editNumber.hint = null
    }

    private fun bindBody() {
        val adapter = StepAdapter(
            onEdit = ::editBodyStep,
            onDelete = { index ->
                if (index in loopBody.indices) {
                    loopBody.removeAt(index)
                    refreshBody()
                }
            },
            labelOf = { index ->
                if (bodyPrefix.isEmpty()) "${index + 1}" else "$bodyPrefix.${index + 1}"
            },
            screen = { screen },
        )
        loopAdapter = adapter
        binding.recyclerLoopBody.layoutManager = LinearLayoutManager(activity)
        binding.recyclerLoopBody.adapter = adapter

        binding.editLoopBodyTimes.doAfterTextChanged {
            if (mutating) return@doAfterTextChanged
            loopTimes = parseLoopTimes(it?.toString())
        }
        binding.btnLoopAddTap.setOnClickListener { addBodyStep(StepKind.TAP) }
        binding.btnLoopAddSwipe.setOnClickListener { addBodyStep(StepKind.SWIPE) }
        binding.btnLoopAddWait.setOnClickListener { addBodyStep(StepKind.WAIT) }

        refreshBody()
    }

    private fun applyInitial(initial: Step?) {
        when (initial) {
            is Step.Tap -> tapPoint = initial.points.first()
            is Step.Swipe -> {
                swipeFrom = initial.points.first()
                swipeTo = initial.points.last()
            }

            else -> Unit
        }
        if (initial is Step.Loop) {
            loopTimes = if (initial.times <= 0) 0 else initial.times
            loopBody.addAll(initial.body)
        }

        // 时长默认值：点击 60ms / 滑动 300ms / 等待 1000ms
        val tapDuration = (initial as? Step.Tap)?.durationMs ?: DEFAULT_TAP_MS
        val swipeDuration = (initial as? Step.Swipe)?.durationMs ?: DEFAULT_SWIPE_MS
        val waitDuration = (initial as? Step.Wait)?.durationMs ?: DEFAULT_WAIT_MS
        binding.tapDuration.editDuration.setText(tapDuration.toString())
        binding.swipeDuration.editDuration.setText(swipeDuration.toString())
        binding.waitDuration.editDuration.setText(waitDuration.toString())

        val tapPreDelay = (initial as? Step.Tap)?.preDelayMs ?: Step.PRE_DELAY_DEFAULT
        val swipePreDelay = (initial as? Step.Swipe)?.preDelayMs ?: Step.PRE_DELAY_DEFAULT
        binding.tapPreDelay.editNumber.setText(ActionDelayField.display(tapPreDelay))
        binding.swipePreDelay.editNumber.setText(ActionDelayField.display(swipePreDelay))
        binding.tapRepeat.editNumber.setText(((initial as? Step.Tap)?.repeat ?: 1).toString())
        binding.swipeRepeat.editNumber.setText(((initial as? Step.Swipe)?.repeat ?: 1).toString())
        mutating = true
        binding.editLoopBodyTimes.setText(loopTimes.toString())
        mutating = false

        if (initial is Step.Loop) refreshBody()
    }

    // ── 坐标 ────────────────────────────────────────────────

    private fun renderCoords() {
        bindCoord(binding.tapCoord, tapPoint)
        bindCoord(binding.swipeFrom, swipeFrom)
        bindCoord(binding.swipeTo, swipeTo)
    }

    /** 双行显示：归一化（存储值）+ 像素（按屏幕尺寸反算）。 */
    private fun bindCoord(block: BlockCoordPickerBinding, point: Point) {
        val (px, py) = CoordDisplay.toPx(point, screen.first, screen.second)
        block.textCoordNorm.text = activity.getString(R.string.coord_norm_format, point.x, point.y)
        block.textCoordPx.text = activity.getString(R.string.coord_px_format, px, py)
    }

    private fun bindPickButtons() {
        binding.tapCoord.btnPickCoord.setOnClickListener {
            startPicker(tapPoint) {
                tapPoint = it
                renderCoords()
            }
        }
        binding.swipeFrom.btnPickCoord.setOnClickListener {
            startPicker(swipeFrom) {
                swipeFrom = it
                renderCoords()
            }
        }
        binding.swipeTo.btnPickCoord.setOnClickListener {
            startPicker(swipeTo) {
                swipeTo = it
                renderCoords()
            }
        }
    }

    private fun startPicker(current: Point, apply: (Point) -> Unit) {
        val coordinatePicker = CoordinatePicker(activity, current)
        picker = coordinatePicker
        val shown = coordinatePicker.show { picked ->
            picker = null
            apply(picked)
        }
        if (!shown) {
            picker = null
            toast(R.string.toast_picker_need_overlay)
        }
    }

    // ── 循环体 ──────────────────────────────────────────────

    private fun addBodyStep(stepKind: StepKind) {
        StepEditDialog(
            activity = activity,
            screen = screen,
            initial = defaultStep(stepKind),
            kind = stepKind,
            bodyPrefix = bodyPrefix,
            defaultDelayMs = defaultDelayMs,
        ) { result ->
            if (result != null) {
                loopBody.add(result)
                refreshBody()
            }
        }.show()
    }

    private fun editBodyStep(index: Int) {
        val step = loopBody.getOrNull(index) ?: return
        if (step is Step.Multi || step is Step.Loop) {
            // TODO(v1.1): 嵌套循环与多指轨迹的循环体内编辑
            toast(R.string.toast_step_unsupported)
            return
        }
        StepEditDialog(
            activity = activity,
            screen = screen,
            initial = step,
            kind = StepKind.of(step),
            bodyPrefix = bodyPrefix,
            defaultDelayMs = defaultDelayMs,
        ) { result ->
            if (result != null && index in loopBody.indices) {
                loopBody[index] = result
                refreshBody()
            }
        }.show()
    }

    private fun refreshBody() {
        loopAdapter?.submit(loopBody)
    }

    // ── 结果 ────────────────────────────────────────────────

    /** 输入框解析：留空 / 负数 → 跟随默认（-1）；否则按数值夹取。 */
    private fun parsePreDelay(text: String?): Long =
        ActionDelayField.parse(text, MAX_DURATION_MS)

    private fun buildStep(): Step? = when (kind) {
        StepKind.TAP -> Step.Tap(
            points = listOf(tapPoint),
            durationMs = durationOf(binding.tapDuration.editDuration, DEFAULT_TAP_MS),
            preDelayMs = parsePreDelay(binding.tapPreDelay.editNumber.text?.toString()),
            repeat = repeatOf(binding.tapRepeat.editNumber),
        )

        StepKind.SWIPE -> Step.Swipe(
            points = listOf(swipeFrom, swipeTo),
            durationMs = durationOf(binding.swipeDuration.editDuration, DEFAULT_SWIPE_MS),
            preDelayMs = parsePreDelay(binding.swipePreDelay.editNumber.text?.toString()),
            repeat = repeatOf(binding.swipeRepeat.editNumber),
        )

        StepKind.WAIT -> Step.Wait(
            durationMs = durationOf(binding.waitDuration.editDuration, DEFAULT_WAIT_MS),
        )

        StepKind.LOOP -> {
            if (loopBody.isEmpty()) {
                toast(R.string.toast_loop_empty_body)
                null
            } else {
                Step.Loop(loopTimes, ArrayList(loopBody))
            }
        }

        StepKind.MULTI -> {
            toast(R.string.toast_step_unsupported)
            null
        }
    }

    private fun durationOf(edit: EditText, fallback: Long): Long {
        val text = edit.text.toString().trim()
        if (text.isEmpty()) return fallback
        return text.toLongOrNull()?.coerceIn(0L, MAX_DURATION_MS) ?: fallback
    }

    /** 空输入或非法值按 1 处理：0 次循环没有意义。 */
    private fun repeatOf(edit: EditText): Int {
        val text = edit.text.toString().trim()
        if (text.isEmpty()) return 1
        return text.toIntOrNull()?.coerceIn(1, MAX_LOOP_TIMES) ?: 1
    }

    private fun visibleIf(condition: Boolean): Int =
        if (condition) View.VISIBLE else View.GONE

    private fun toast(resId: Int) = showToast(activity, resId)

    companion object {
        private const val DEFAULT_TAP_MS = 60L
        private const val DEFAULT_SWIPE_MS = 300L
        private const val DEFAULT_WAIT_MS = 1_000L
        private const val MAX_DURATION_MS = 60_000L

        /** 新建步骤的初值：点击屏幕中心、滑动自上而下、等待 1 秒、循环 3 次。 */
        fun defaultStep(kind: StepKind): Step = when (kind) {
            StepKind.TAP -> Step.Tap(
                points = listOf(Point(0.5f, 0.5f)),
                durationMs = DEFAULT_TAP_MS,
            )

            StepKind.SWIPE -> Step.Swipe(
                points = listOf(Point(0.5f, 0.75f), Point(0.5f, 0.25f)),
                durationMs = DEFAULT_SWIPE_MS,
            )

            StepKind.WAIT -> Step.Wait(DEFAULT_WAIT_MS)
            StepKind.LOOP -> Step.Loop(3, emptyList())
            StepKind.MULTI -> Step.Multi(
                listOf(listOf(Point(0.5f, 0.5f))),
                DEFAULT_TAP_MS,
            )
        }
    }
}

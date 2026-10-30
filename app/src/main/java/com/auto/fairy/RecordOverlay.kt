package com.auto.fairy

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import com.auto.fairy.core.compiler.ScriptCompiler
import com.auto.fairy.core.model.ScreenInfo
import com.auto.fairy.core.model.Script
import com.auto.fairy.core.model.Step
import com.auto.fairy.databinding.OverlayRecordBinding
import com.auto.fairy.overlay.CoordinateMarkersController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏录制浮层。
 *
 * 统一走透明浮层（含 API 34+），原因：系统级 MotionEvent 采集需要额外权限且
 * 各 ROM 行为不一，M2 先用冻结底层触摸的方式保证录制数据可靠。
 * TODO(M3)：API 34+ 接入 AccessibilityService 的 motion event 采集。
 */
object RecordOverlay {

    /** 坐标层互斥来源标识。 */
    private const val REASON_RECORD = "record"

    private const val TAG = "RecordOverlay"

    private var windowManager: WindowManager? = null
    private var binding: OverlayRecordBinding? = null
    private var kernel: RecordKernel? = null
    private var appContext: Context? = null
    private var onFinished: ((Script?) -> Unit)? = null

    private val steps = ArrayList<Step>()
    /** 上一次手势抬起的事件时刻；0 = 还没有步骤。 */
    private var lastGestureEndAt = 0L
    /** 当前手势按下的事件时刻：间断以此为准，才不含本步自身的按压时长。 */
    private var gestureDownAt = 0L
    private var recordingStartAt = 0L
    private var active = false

    /** 草稿的名字与屏幕：只在开始录制时算一次，草稿与最终脚本共用同一份。 */
    private var draftName = ""
    private var draftScreen: ScreenInfo? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val view = binding ?: return
            val elapsed = SystemClock.uptimeMillis() - recordingStartAt
            view.textRecordDuration.text = formatDuration(elapsed)
            view.textRecordSteps.text =
                view.root.context.getString(R.string.record_steps_format, recordedCount())
            if (active) handler.postDelayed(this, 250L)
        }
    }

    fun isRecording(): Boolean = active

    fun start(context: Context, onFinished: (Script?) -> Unit): Boolean {
        if (active) return false
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, R.string.toast_recording_needs_overlay, Toast.LENGTH_LONG).show()
            return false
        }
        val wm = app.getSystemService(WindowManager::class.java)
        windowManager = wm
        appContext = app
        this.onFinished = onFinished
        steps.clear()
        lastGestureEndAt = 0L
        gestureDownAt = 0L
        // 名字与屏幕只算一次：草稿每步覆写时不必重算，最终脚本也用同一份
        draftName = app.getString(
            R.string.dialog_recorded_script_name,
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()),
        )
        draftScreen = ScreenMetrics.screenInfo(app)
        // 录制期间必须独占触摸：收起悬浮窗（内含停回放与收坐标层）、关掉脚本列表浮层，
        // 避免多个浮层抢事件（M5 §3.3）；全局标记层同样让位（M6 §4.3）
        OverlayScriptList.close()
        OverlayController.hide()
        // 录制全屏吃触摸，坐标标记层让位（退出时按用户开关恢复）
        CoordinateMarkersController.setSuppressed(app, REASON_RECORD, true)

        val size = ScreenMetrics.size(app)
        val density = app.resources.displayMetrics.density
        kernel = RecordKernel(size.x, size.y, density)

        val view = OverlayRecordBinding.inflate(android.view.LayoutInflater.from(app))
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )

        binding = view
        view.recordTouchLayer.setOnTouchListener { _, event -> handleTouch(event) }
        // 信息条/操作条自身消费触摸，避免录制坐标时把按钮点击算成手势
        view.recordBanner.setOnClickListener { }
        view.recordActions.setOnClickListener { }
        view.btnRecordCancel.setOnClickListener { finish(save = false) }
        view.btnRecordDone.setOnClickListener { finish(save = true) }

        return try {
            wm.addView(view.root, layoutParams)
            active = true
            recordingStartAt = SystemClock.uptimeMillis()
            view.textRecordSteps.text = app.getString(R.string.record_steps_format, 0)
            view.textRecordDuration.text = formatDuration(0L)
            handler.post(ticker)
            true
        } catch (t: Throwable) {
            binding = null
            kernel = null
            this.onFinished = null
            // 窗口没加上：立即释放互斥，否则坐标层会一直以为「录制中」，再也挂不回来
            CoordinateMarkersController.setSuppressed(app, REASON_RECORD, false)
            false
        }
    }

    // ── 触摸采集 ────────────────────────────────────────────

    private fun handleTouch(event: MotionEvent): Boolean {
        val k = kernel ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownAt = event.eventTime
                k.onDown(event.rawX, event.rawY, event.eventTime, event.pointerCount)
            }

            MotionEvent.ACTION_POINTER_DOWN -> k.onPointerDown(event.pointerCount)
            MotionEvent.ACTION_MOVE -> k.onMove(event.rawX, event.rawY, event.pointerCount)
            MotionEvent.ACTION_UP -> {
                val step = k.onUp(event.rawX, event.rawY, event.eventTime)
                if (step != null) appendStep(step, event.eventTime)
                // 掉手势（多指/异常）也要推进锚点，否则下一段间隔把这次整套手势算进去。
                // 一条还没录时保持 0：[RecordGap.between] 对 0 锚点返回 null，不会空排一个 Wait。
                else if (steps.isNotEmpty()) lastGestureEndAt = event.eventTime
            }

            MotionEvent.ACTION_CANCEL -> {
                k.onCancel()
                // 被系统打断同样算掉手势：不推进锚点，下一段间隔会把这次按住整段算进去。
                // 同 UP 分支：还没录到步骤时保持 0（RecordGap.between 对 0 返回 null）。
                if (steps.isNotEmpty()) lastGestureEndAt = event.eventTime
            }
        }
        return true
    }

    private fun appendStep(step: Step, eventTime: Long) {
        // 间隔量的是「上次抬起 → 这次按下」，不能用本次抬起时刻（那会把本步的按压
        // 时长也记进等待里：录 800ms 的滑动会凭空多等 800ms）。
        RecordGap.between(lastGestureEndAt, gestureDownAt)?.let { steps.add(Step.Wait(it)) }
        steps.add(step)
        lastGestureEndAt = eventTime
        writeDraft()
        val view = binding ?: return
        view.textRecordSteps.text =
            view.root.context.getString(R.string.record_steps_format, recordedCount())
    }

    /** 每步覆写：草稿只有几十步、几 KB，主线程同步写即可（写完才轮到下一次手势）。 */
    private fun writeDraft() {
        val app = appContext ?: return
        val screen = draftScreen ?: return
        runCatching { RecordDraft.save(app.filesDir, steps, screen, draftName) }
            .onFailure { android.util.Log.w(TAG, "草稿写入失败", it) }
    }

    // ── 结束 ────────────────────────────────────────────────

    private fun finish(save: Boolean) {
        if (!active) return
        active = false
        handler.removeCallbacks(ticker)
        val view = binding
        val callback = onFinished
        val recorded = ArrayList(steps)
        val app = appContext

        binding = null
        kernel = null
        onFinished = null
        appContext = null
        steps.clear()
        if (view != null) {
            runCatching { windowManager?.removeView(view.root) }
        }
        windowManager = null
        // 录制窗已摘掉：坐标层按开关恢复，但录制前已收小窗，闸门会挡住——录制结束不等于激活
        app?.let { CoordinateMarkersController.setSuppressed(it, REASON_RECORD, false) }

        if (!save || recorded.isEmpty() || app == null) {
            if (save && recorded.isEmpty()) {
                app?.let {
                    showToast(it, R.string.toast_record_empty)
                }
            }
            // 主动取消 / 什么都没录：草稿没有保留价值，留着反而下次进来先弹恢复框
            app?.let { RecordDraft.clear(it.filesDir) }
            callback?.invoke(null)
            return
        }

        val name = draftName
        // 存盘前先过一遍编译器：录到的手势若超出后端能力（例如超长滑动），
        // 在这里就拒绝，避免列表里出现一个永远跑不起来的脚本。
        val candidate = Script(
            id = "",
            name = name,
            screen = draftScreen ?: ScreenMetrics.screenInfo(app),
            steps = recorded,
            // 录制已经把「两次手势之间的间隔」显式记成 Wait 步骤，动作延迟必须归零：
            // 默认 1000ms 会给每个动作再加一段空白，录到的 300ms 间隔回放成 1340ms。
            // 步骤本身仍保持「跟随默认」，想整体放慢只改这一个值。
            defaultActionDelayMs = 0L,
        )
        try {
            ScriptCompiler.compile(candidate)
        } catch (t: Throwable) {
            // 编译不过：此时草稿是这份内容唯一的副本，绝不能删
            Toast.makeText(
                app,
                app.getString(R.string.toast_import_failed, t.message ?: "脚本无法编译"),
                Toast.LENGTH_LONG,
            ).show()
            callback?.invoke(null)
            return
        }
        val script = AutoFairyApp.of(app).repository.saveOrNull(candidate)
        if (script == null) {
            // 写盘失败：录制内容已在内存里，提示用户而不是让异常崩掉整个进程。
            // 草稿同样保留——它是用户重试或恢复的唯一依据。
            Toast.makeText(app, R.string.toast_save_failed, Toast.LENGTH_LONG).show()
            callback?.invoke(null)
            return
        }
        RecordDraft.clear(app.filesDir)
        showToast(app, app.getString(R.string.toast_record_saved, script.name))
        callback?.invoke(script)
    }

    /** 计数口径与列表卡片一致：延时也算一步（录制会自动插入延时）。 */
    private fun recordedCount(): Int = StepCount.total(steps)

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000L
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

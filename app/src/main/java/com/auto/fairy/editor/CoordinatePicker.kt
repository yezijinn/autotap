package com.auto.fairy.editor

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import com.auto.fairy.OverlayController
import com.auto.fairy.ScreenMetrics
import com.auto.fairy.core.model.Point
import com.auto.fairy.databinding.OverlayCoordinatePickerBinding
import com.auto.fairy.overlay.CoordinateMarkersController

/**
 * 取坐标浮层：`TYPE_APPLICATION_OVERLAY` 全屏窗口，手指落点即目标点。
 *
 * 与 M2 录制浮层同机制：遮罩层吃touch事件，阻止透传到底层应用；
 * 进入前收起迷你条与坐标标记层，避免浮层抢触摸。退出只还标记层——
 * 迷你条不在这里恢复，否则取完点就压在宿主的编辑器上（见 [show]）。
 */
class CoordinatePicker(
    private val activity: Activity,
    initial: Point?,
) {

    private val appContext: Context = activity.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(WindowManager::class.java)
    /** 触摸事件坐标即设备真实像素坐标，读数与归一化都以它为准。 */
    private val device = ScreenMetrics.size(appContext)

    private var binding: OverlayCoordinatePickerBinding? = null
    private var point: Point = initial ?: Point(0.5f, 0.5f)
    private var onPicked: ((Point) -> Unit)? = null

    /** 坐标层互斥来源标识。 */
    private val reason = "picker"

    fun isShowing(): Boolean = binding != null

    /** @return false 表示没有悬浮窗权限或添加失败，调用方应提示用户。 */
    fun show(onPicked: (Point) -> Unit): Boolean {
        if (binding != null) return true
        if (!Settings.canDrawOverlays(appContext)) return false

        val view = OverlayCoordinatePickerBinding.inflate(android.view.LayoutInflater.from(appContext))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )

        this.onPicked = onPicked
        // 取坐标全屏吃触摸：迷你条让位。这里只藏不还——还窗口的活儿归宿主
        // （编辑器 / 面板在 onDestroy 各自 showBall，见 M5 互斥表）。
        // 曾在这里按「进来时可见」无条件 showBall，结果取完坐标确认后，迷你条
        // 直接复活压在编辑器上，用户点哪儿都被它挡住。
        OverlayController.hidePanel()
        // 坐标标记层同样让位：全屏取点窗不能被满屏标记干扰
        CoordinateMarkersController.setSuppressed(appContext, reason, true)

        view.pickerTouchLayer.setOnTouchListener { _, event -> handleTouch(event) }
        view.btnPickerConfirm.setOnClickListener {
            val picked = point
            val callback = onPicked
            dismiss()
            callback(picked)
        }
        view.btnPickerCancel.setOnClickListener { cancel() }
        view.textPickerScreen.text = appContext.getString(
            com.auto.fairy.R.string.picker_screen_format,
            device.x,
            device.y,
        )

        return try {
            windowManager.addView(view.root, params)
            binding = view
            // 初始点直接显示出来，用户不必先摸一下屏幕才知道准星在哪
            val (px, py) = CoordDisplay.toPx(point, device.x, device.y)
            render(px.toFloat(), py.toFloat())
            true
        } catch (t: Throwable) {
            this.onPicked = null
            restoreMarkers()
            false
        }
    }

    fun cancel() {
        if (binding == null) return
        dismiss()
    }

    private fun dismiss() {
        val view = binding ?: return
        binding = null
        onPicked = null
        runCatching { windowManager.removeView(view.root) }
        restoreMarkers()
    }

    private fun restoreMarkers() {
        // 取点结束（含添加窗口失败）：坐标标记层按用户开关恢复；迷你条不动，
        // 仍由宿主 Activity 关闭时恢复（见 [show]）
        CoordinateMarkersController.setSuppressed(appContext, reason, false)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.rawX.coerceIn(0f, device.x.toFloat())
                val y = event.rawY.coerceIn(0f, device.y.toFloat())
                point = CoordDisplay.fromPx(x, y, device.x, device.y)
                render(x, y)
            }

            else -> Unit
        }
        // 吃掉全部触摸，绝不让事件透传到底层应用（与记录浮层一致）
        return true
    }

    private fun render(x: Float, y: Float) {
        val view = binding ?: return
        view.pickerCrosshair.setPoint(x, y)
        view.textPickerPx.text = appContext.getString(
            com.auto.fairy.R.string.picker_px_format,
            x.toInt(),
            y.toInt(),
        )
        view.textPickerNorm.text = appContext.getString(
            com.auto.fairy.R.string.picker_norm_format,
            point.x,
            point.y,
        )
    }
}

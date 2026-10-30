package com.auto.fairy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.auto.fairy.databinding.ActivityPanelBinding
import com.auto.fairy.overlay.CoordinateMarkersController

/**
 * 诊断 / 保活 / 设置的浮层容器（宽 90%、高 70% 的居中卡片）。
 *
 * 为什么用「透明 Dialog 风格 Activity」而不是 `Dialog`：
 * 1. 列表浮层与悬浮球都只有 application context，`Dialog` 拿不到可用的窗口 token；
 * 2. 三个页面内部有跳系统设置、输入法、返回键等 Activity 语义；
 * 3. 内容继续沿用已有布局 `fragment_diagnose` / `fragment_keepalive` / `fragment_settings`，
 *    不复制文案与逻辑。
 * 主题 [R.style.Theme_AutoFairy_SheetDialog] 让窗口透明、无标题、自带遮罩点击关闭。
 */
class PanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPanelBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 打开面板即暂停：注入点击打在开关上会直接改写偏好（见 PlaybackController.pauseForModal）
        PlaybackController.pauseForModal()
        // 模态浮层出现时迷你条一律隐藏（M5 §3.3 互斥约定）
        OverlayController.hidePanel()
        // 坐标标记层同样让位：满屏红点压在卡片上会挡住设置项与按钮
        CoordinateMarkersController.setSuppressed(this, REASON_PANEL, true)

        val screen = ScreenMetrics.size(this)
        binding.panelCard.layoutParams = FrameLayout.LayoutParams(
            (screen.x * ScreenMetrics.CARD_WIDTH_RATIO).toInt(),
            (screen.y * ScreenMetrics.CARD_HEIGHT_RATIO).toInt(),
            Gravity.CENTER,
        )
        // 卡片吃掉点击，否则会冒泡到遮罩被当成「点外部」直接关掉
        binding.panelCard.setOnClickListener { }
        // 回放中注入的点击打不到这里：[dispatchTouchEvent] 在 injecting 期间丢弃，
        // 遮罩因此不会被脚本点掉。丢弃前会先把回放停下（见 [dispatchTouchEvent]），
        // 所以真实用户第一次点击虽被吃掉，下一笔起面板就恢复正常可点。
        binding.panelRoot.setOnClickListener { finish() }
        binding.btnPanelClose.setOnClickListener { finish() }

        val panel = intent.getStringExtra(EXTRA_PANEL) ?: PANEL_DIAGNOSE
        binding.textPanelTitle.setText(titleOf(panel))

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.panel_container, fragmentOf(panel), panel)
                .commit()
        }
    }

    override fun onDestroy() {
        // 模态浮层关闭：控制条恢复常驻（onCreate 里 hidePanel 的逆操作）
        OverlayController.showBall()
        CoordinateMarkersController.setSuppressed(this, REASON_PANEL, false)
        // 回放是被本面板暂停的：不自动恢复，至少要告诉用户它还在暂停
        PlaybackController.noticeStillPausedAfterModal(this)
        super.onDestroy()
    }

    /**
     * 打开面板后的头一片注入点击一律丢弃。
     *
     * [PlaybackController.pauseForModal] 只能把回放停到**片边界**（片长 ≤400ms），
     * 这一片里还没派发的点击照旧落下来：只暂停不挡时，3 次复验里仍有 1 次把
     * `coord_visible` 改回了 true。注入真的停下（引擎转 PAUSED）后 `injecting`
     * 自己变假，这里自动恢复，不需要定时器。返回键不走这条路径，用户随时能关面板。
     *
     * 面板开着时又按音量键恢复播放：再来的一击把它重新暂停，然后照旧吞掉。
     * 否则整窗（含关闭按钮）一起失灵——脚本点击也早被暂停挡住，这一击只可能是
     * 用户的操作（想把媒体音量调小），吞掉它并停在暂停才是唯一出口。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (shieldModalTouch(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun titleOf(panel: String): Int = when (panel) {
        PANEL_KEEPALIVE -> R.string.nav_title_keepalive
        PANEL_SETTINGS -> R.string.nav_title_settings
        else -> R.string.nav_title_diagnose
    }

    private fun fragmentOf(panel: String): Fragment = when (panel) {
        PANEL_KEEPALIVE -> KeepAliveFragment()
        PANEL_SETTINGS -> SettingsFragment()

        else -> DiagnoseFragment()
    }

    companion object {
        const val PANEL_DIAGNOSE = "diagnose"
        const val PANEL_KEEPALIVE = "keepalive"
        const val PANEL_SETTINGS = "settings"

        private const val EXTRA_PANEL = "panel"

        /** 坐标层互斥来源标识。 */
        private const val REASON_PANEL = "panel"

        fun intent(context: Context, panel: String): Intent =
            Intent(context, PanelActivity::class.java).putExtra(EXTRA_PANEL, panel)

        /** 供悬浮层（无 Activity 上下文）与 MainActivity 共用。 */
        fun open(context: Context, panel: String) {
            runCatching {
                context.startActivity(
                    intent(context, panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

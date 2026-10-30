package com.auto.fairy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.auto.fairy.core.inject.Health
import com.auto.fairy.core.inject.LatencyClass
import com.auto.fairy.core.runtime.PlaybackEngine
import com.auto.fairy.databinding.FragmentDiagnoseBinding
import java.util.Locale

/**
 * 诊断页：后端/播报/设备三段只读信息 + 保活自检（设计方案 4.3）。
 *
 * 自检的判定依据是「进程标识」而不是只看时间戳：自检开始时把当前进程的随机
 * token 写进 SharedPreferences，回来时若内存里的 token 与之不同，说明进程确实
 * 被回收重启过。无法确证的情况一律显示「无法确认」，不谎报存活。
 */
class DiagnoseFragment : Fragment() {

    private var binding: FragmentDiagnoseBinding? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = FragmentDiagnoseBinding.inflate(inflater, container, false)
        binding = view
        return view.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.btnSelfcheck?.setOnClickListener { startSelfCheck() }
        refreshDevice()
        // 「点击抖动」是通道下限的静态说明（不是运行时统计），一次填完即可
        binding?.textJitter?.text =
            getString(R.string.diagnose_jitter_format, A11yBackend.MIN_GAP_MS)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        evaluateSelfCheck()
        refresh()
        PlaybackController.addListener(playbackListener)
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    override fun onPause() {
        PlaybackController.removeListener(playbackListener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroyView() {
        PlaybackController.removeListener(playbackListener)
        handler.removeCallbacksAndMessages(null)
        binding = null
        super.onDestroyView()
    }

    /**
     * 巡数直接跟回放的步边界回调走：1s 轮询只会让读数慢半拍到一秒。
     * 其它段落（后端/能力/自检）变化慢，仍由 [ticker] 定期刷新。
     */
    private val playbackListener = object : PlaybackController.Listener {
        override fun onPlaybackState(state: PlaybackEngine.State, scriptId: String?) = refreshCycle()

        override fun onProgress(cycle: Long) = refreshCycle()

        override fun onError(message: String) = refresh()

        override fun onNotice(message: String) = Unit
    }

    // ── 状态 ────────────────────────────────────────────────

    private fun refresh() {
        val view = binding ?: return
        val context = view.root.context

        val systemEnabled = PermissionStatus.isA11yEnabled(context)
        val backendReady = PlaybackController.isBackendReady()
        val (a11yText, a11yColor) = when {
            systemEnabled && backendReady -> R.string.diagnose_a11y_connected to R.color.status_running
            systemEnabled -> R.string.diagnose_a11y_enabled_not_connected to R.color.status_paused
            else -> R.string.diagnose_a11y_off to R.color.status_error
        }
        view.textA11yState.setText(a11yText)
        view.textA11yState.setTextColor(ContextCompat.getColor(context, a11yColor))

        val caps = PlaybackController.caps()
        val latency = when (caps.latencyClass) {
            LatencyClass.LOW -> R.string.diagnose_latency_low
            LatencyClass.NORMAL -> R.string.diagnose_latency_normal
            LatencyClass.HIGH -> R.string.diagnose_latency_high
        }
        view.textCaps.text = context.getString(
            R.string.diagnose_caps_format,
            caps.maxStrokesPerBatch,
            caps.maxBatchDurationMs / 1000L,
            caps.maxSliceMs,
            context.getString(latency),
        )

        val health = PlaybackController.health()
        view.textHealth.setText(
            when (health) {
                Health.ALIVE -> R.string.overlay_health_alive
                Health.DEAD -> R.string.overlay_health_dead
            },
        )
        view.textHealth.setTextColor(
            ContextCompat.getColor(
                context,
                when (health) {
                    Health.ALIVE -> R.color.status_running
                    Health.DEAD -> R.color.status_error
                },
            ),
        )

        val state = PlaybackController.state
        view.textPlayState.setText(
            when (state) {
                PlaybackEngine.State.RUNNING -> R.string.status_playing
                PlaybackEngine.State.PAUSED -> R.string.status_paused_label
                PlaybackEngine.State.IDLE -> R.string.overlay_state_ready
            },
        )
        refreshCycle()
        // 游标只剩「片」有意义：巡数上面已经有了（同一个数不写两遍），
        // 空闲时写「无」——否则「第 0 片」会被读成漏掉的首片
        view.textCursor.text = if (state == PlaybackEngine.State.IDLE) {
            context.getString(R.string.diagnose_none)
        } else {
            context.getString(R.string.diagnose_cursor_format, PlaybackController.cursor().sliceIndex + 1)
        }

        val error = PlaybackController.lastError()
        view.textLastError.text = error ?: context.getString(R.string.diagnose_none)
        view.textLastError.setTextColor(
            ContextCompat.getColor(
                context,
                if (error == null) R.color.text_primary else R.color.status_error,
            ),
        )
    }

    /** 只刷巡数两处读数：回放每推进一步/每换一巡都会调到这里，必须足够轻。 */
    private fun refreshCycle() {
        val view = binding ?: return
        val state = PlaybackController.state
        val completed = PlaybackController.cursorCycle()
        val current = if (state == PlaybackEngine.State.IDLE) 0L else completed + 1L
        view.textCycle.text = view.root.context.getString(
            R.string.diagnose_cycle_format,
            current,
            completed,
        )
    }

    private fun refreshDevice() {
        val view = binding ?: return
        val context = view.root.context
        view.textDevice.text = context.getString(
            R.string.diagnose_device_format,
            android.os.Build.MODEL,
            android.os.Build.MANUFACTURER,
        )
        view.textAndroid.text = context.getString(
            R.string.diagnose_android_format,
            android.os.Build.VERSION.RELEASE,
            android.os.Build.VERSION.SDK_INT,
        )
        val wm = context.getSystemService(WindowManager::class.java)
        val size = ScreenMetrics.size(context)
        @Suppress("DEPRECATION")
        val refreshRate = wm?.defaultDisplay?.refreshRate ?: 0f
        view.textScreen.text = String.format(
            Locale.US,
            context.getString(R.string.diagnose_screen_format),
            size.x,
            size.y,
            refreshRate,
        )
    }

    // ── 自检 ────────────────────────────────────────────────

    private fun startSelfCheck() {
        val view = binding ?: return
        AppSettings.beginSelfCheck(
            view.root.context,
            wasPlaying = PlaybackController.state != PlaybackEngine.State.IDLE,
        )
        setSelfCheckResult(
            view.root.context.getString(
                R.string.diagnose_selfcheck_running,
                duration(0L),
            ),
            R.color.status_paused,
        )
        Toast.makeText(view.root.context, R.string.diagnose_selfcheck_toast, Toast.LENGTH_LONG)
            .show()
    }

    private fun evaluateSelfCheck() {
        val view = binding ?: return
        val context = view.root.context
        val pending = AppSettings.pendingSelfCheck(context)

        if (pending == null) {
            val last = AppSettings.lastSelfCheckResult(context)
            if (last != null) {
                setSelfCheckResult(last, R.color.text_primary)
            } else {
                setSelfCheckResult(
                    context.getString(R.string.diagnose_selfcheck_none),
                    R.color.text_secondary,
                )
            }
            return
        }

        val elapsed = SystemClock.elapsedRealtime() - pending.elapsedMs
        if (elapsed < 0L) {
            finish(context, context.getString(R.string.diagnose_selfcheck_rebooted), R.color.status_paused)
            return
        }
        // 进程标识变化是硬证据：进程确实重启过，与离开多久无关，先给结论。
        val sameProcess = pending.processToken == ProcessIdentity.token
        if (!sameProcess) {
            finish(
                context,
                context.getString(R.string.diagnose_selfcheck_killed, duration(elapsed)),
                R.color.status_error,
            )
            return
        }
        if (elapsed < MIN_CHECK_MS) {
            setSelfCheckResult(
                context.getString(R.string.diagnose_selfcheck_running, duration(elapsed)),
                R.color.status_paused,
            )
            return
        }

        val backendAlive = PlaybackController.isBackendReady()
        val wasPlayingNowIdle =
            pending.wasPlaying && PlaybackController.state == PlaybackEngine.State.IDLE

        val base: String
        val color: Int
        if (backendAlive) {
            base = context.getString(R.string.diagnose_selfcheck_survived, duration(elapsed))
            color = R.color.status_running
        } else {
            // 进程还在但服务断了：不能算「存活通过」，也不能断言被杀
            base = context.getString(R.string.diagnose_selfcheck_process_only, duration(elapsed))
            color = R.color.status_paused
        }
        val text = if (wasPlayingNowIdle) {
            base + context.getString(R.string.diagnose_selfcheck_playback_lost)
        } else {
            base
        }
        finish(context, text, color)
    }

    private fun finish(context: android.content.Context, text: String, color: Int) {
        AppSettings.finishSelfCheck(context, text)
        setSelfCheckResult(text, color)
    }

    private fun setSelfCheckResult(text: String, colorRes: Int) {
        val view = binding ?: return
        view.textSelfcheckResult.text = text
        view.textSelfcheckResult.setTextColor(
            ContextCompat.getColor(view.root.context, colorRes),
        )
    }

    private fun duration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val minutes = total / 60L
        val seconds = total % 60L
        return if (minutes > 0L) {
            getString(R.string.diagnose_duration_min_sec, minutes, seconds)
        } else {
            getString(R.string.diagnose_duration_sec, seconds)
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 1_000L

        /** 低于 1 分钟的离开时间不足以说明保活，继续保留自检。 */
        private const val MIN_CHECK_MS = 60_000L
    }
}

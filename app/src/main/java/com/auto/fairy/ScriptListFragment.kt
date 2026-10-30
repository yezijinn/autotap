package com.auto.fairy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.auto.fairy.core.runtime.PlaybackEngine
import com.auto.fairy.databinding.FragmentScriptListBinding
import com.auto.fairy.editor.ScriptEditorActivity
import com.auto.fairy.overlay.CoordinateMarkersController

/**
 * 主页：脚本列表。
 *
 * 坐标不再在这里画：标记已迁到系统级浮层（[com.auto.fairy.overlay.CoordinateMarkersController]），
 * 激活脚本后跨页面可见，故本页只负责列表与四个卡片动作。
 */
class ScriptListFragment : Fragment() {

    private var binding: FragmentScriptListBinding? = null
    private lateinit var repository: ScriptRepository
    private lateinit var adapter: ScriptAdapter
    private var pendingExportId: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri == null || id == null) return@registerForActivityResult
        val ok = runCatching { repository.export(uri, id) }.getOrDefault(false)
        toast(if (ok) R.string.toast_export_ok else R.string.toast_export_failed)
    }

    private val playbackListener = object : PlaybackController.Listener {
        override fun onPlaybackState(state: PlaybackEngine.State, scriptId: String?) {
            updatePlaybackUi()
        }

        override fun onProgress(cycle: Long) {
            throttledProgressUpdate()
        }

        override fun onError(message: String) {
            toastText(message)
        }

        override fun onNotice(message: String) {
            toastText(message)
        }
    }

    private val repositoryListener: () -> Unit = {
        refreshList(scrollToPlaying = false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = FragmentScriptListBinding.inflate(inflater, container, false)
        binding = view
        return view.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = AutoFairyApp.of(requireContext()).repository
        adapter = ScriptAdapter(
            onActivate = ::activate,
            onEdit = { entry ->
                if (entry.broken) {
                    toast(R.string.toast_script_broken)
                } else {
                    startActivity(ScriptEditorActivity.intent(requireActivity(), entry.id))
                }
            },
            onExport = ::export,
            onDelete = ::confirmDelete,
        )
        binding?.recyclerScripts?.layoutManager = LinearLayoutManager(requireContext())
        binding?.recyclerScripts?.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        PlaybackController.addListener(playbackListener)
        repository.addListener(repositoryListener)
        refreshList(scrollToPlaying = true)
    }

    override fun onPause() {
        progressHandler.removeCallbacks(progressFlush)
        progressPending = false
        PlaybackController.removeListener(playbackListener)
        repository.removeListener(repositoryListener)
        super.onPause()
    }

    override fun onDestroyView() {
        binding?.recyclerScripts?.adapter = null
        binding = null
        super.onDestroyView()
    }

    // ── 列表 ────────────────────────────────────────────────

    private fun refreshList(scrollToPlaying: Boolean) {
        val view = binding ?: return
        // 损坏的脚本也要列出来，否则它在主页整行消失、用户只能看到播放报错
        val entries = repository.list(includeBroken = true)
        val playing = PlaybackController.playTargetId
        adapter.submit(
            entries = entries,
            playingId = playing,
            state = PlaybackController.state,
            hasError = PlaybackController.lastError() != null,
        )
        view.emptyScripts.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        view.recyclerScripts.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        if (scrollToPlaying && playing != null) {
            val index = adapter.positionOf(playing)
            if (index >= 0) view.recyclerScripts.scrollToPosition(index)
        }
    }

    private val progressHandler = android.os.Handler(Looper.getMainLooper())
    private var progressPending = false
    private val progressFlush = Runnable {
        progressPending = false
        if (!isAdded) return@Runnable
        updatePlaybackUi()
    }

    /** 进度刷新节流：循环切巡可能 1 秒内来多次，合并到 100ms 刷一次足够肉眼。 */
    private fun throttledProgressUpdate() {
        if (progressPending) return
        progressPending = true
        progressHandler.postDelayed(progressFlush, PROGRESS_THROTTLE_MS)
    }

    private fun updatePlaybackUi() {
        adapter.updatePlayback(
            PlaybackController.playTargetId,
            PlaybackController.state,
            PlaybackController.lastError() != null,
            PlaybackController.cursorCycle(),
        )
    }

    // ── 卡片动作 ────────────────────────────────────────────

    /**
     * 激活：选中脚本并唤出迷你悬浮窗，开始播放只在小窗上触发。
     * 与浮层列表同一口径，全应用只有小悬浮窗一个「开始」入口。
     */
    private fun activate(entry: ScriptRepository.Entry) {
        if (entry.broken) {
            toast(R.string.toast_script_broken)
            return
        }
        repository.lastPlayedId = entry.id
        // 没有悬浮窗权限时 show() 已经说明了原因：不能再报「已激活」，小窗根本没出现
        if (OverlayController.show(requireContext())) toast(R.string.toast_script_activated)
    }

    private fun export(entry: ScriptRepository.Entry) {
        if (entry.broken) {
            toast(R.string.toast_script_broken)
            return
        }
        pendingExportId = entry.id
        val safeName = entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        exportLauncher.launch(if (safeName.isBlank()) "script.json" else "$safeName.json")
    }

    private fun confirmDelete(entry: ScriptRepository.Entry) {
        val context = context ?: return
        // 打开确认框即暂停：注入点击会点到「删除」（脚本当场没了）
        PlaybackController.pauseForModal()
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, entry.name))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                if (PlaybackController.playingScriptId == entry.id) PlaybackController.stop()
                repository.delete(entry.id)
                toast(R.string.toast_script_deleted)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        // 弹窗是模态小卡：坐标层让位，避免红点压在确认文案上
        dialog.setOnDismissListener {
            CoordinateMarkersController.setSuppressed(context, REASON_DIALOG, false)
        }
        // 窗口外一击不算取消：回放中那是注入的点击，落到下面的列表行就是「误触编辑/删除」。
        // 空闲时保持原样（点外部收起），与四处遮罩同一判据。
        dialog.setCanceledOnTouchOutside(!PlaybackController.injecting)
        CoordinateMarkersController.setSuppressed(context, REASON_DIALOG, true)
        dialog.show()
        // 按钮本身也要挡：确认框是独立窗口，上面的 setCanceledOnTouchOutside 只管「点窗口外」
        dialog.shieldAgainstInjection()
    }

    private fun toast(resId: Int) = showToast(context, resId)

    private fun toastText(text: String) = showToast(context, text)

    private companion object {
        /** 巡数刷新节流窗口：肉眼够用，避免高频回调打满主线程。 */
        const val PROGRESS_THROTTLE_MS = 100L

        /** 坐标层互斥来源标识。 */
        const val REASON_DIALOG = "dialog"
    }
}

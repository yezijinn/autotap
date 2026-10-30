package com.auto.fairy

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.auto.fairy.core.runtime.PlaybackEngine
import com.auto.fairy.databinding.ItemScriptCardBinding

/** 脚本卡片适配器：状态高亮与状态点全部由 [PlaybackController] 的当前状态驱动。 */
class ScriptAdapter(
    private val onActivate: (ScriptRepository.Entry) -> Unit,
    private val onEdit: (ScriptRepository.Entry) -> Unit,
    private val onExport: (ScriptRepository.Entry) -> Unit,
    private val onDelete: (ScriptRepository.Entry) -> Unit,
) : RecyclerView.Adapter<ScriptAdapter.Holder>() {

    private val items = ArrayList<ScriptRepository.Entry>()
    private var playingId: String? = null
    private var playbackState: PlaybackEngine.State = PlaybackEngine.State.IDLE
    private var hasError = false

    /** 上一次画进卡片状态的巡数：只在巡数真的变了才刷那张卡。 */
    private var progressCycle = -1L

    fun submit(
        entries: List<ScriptRepository.Entry>,
        playingId: String?,
        state: PlaybackEngine.State,
        hasError: Boolean,
    ) {
        items.clear()
        items.addAll(entries)
        this.playingId = playingId
        this.playbackState = state
        this.hasError = hasError
        notifyDataSetChanged()
    }

    /**
     * 只更新播放相关字段。
     *
     * 状态变化才整表重绑；播放中的巡数变化只重绑「正在播放的那张卡」，并且带 payload
     * （RecyclerView 因此复用同一个 ViewHolder，不跑 change 动画）——否则每 500ms
     * 一次进度回调都会让卡片淡入淡出闪一下。
     */
    fun updatePlayback(id: String?, state: PlaybackEngine.State, hasError: Boolean, cycle: Long) {
        val changed = playingId != id || playbackState != state || this.hasError != hasError
        playingId = id
        playbackState = state
        this.hasError = hasError
        if (changed) {
            progressCycle = cycle
            notifyDataSetChanged()
            return
        }
        if (state != PlaybackEngine.State.RUNNING || cycle == progressCycle) return
        progressCycle = cycle
        val position = positionOf(id ?: "")
        if (position >= 0) notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    fun positionOf(id: String): Int = items.indexOfFirst { it.id == id }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemScriptCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    /** payload 刷新：只换状态文案，不动背景与其他字段（避免整卡重绑）。 */
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(items[position])
            return
        }
        val entry = items[position]
        val active = entry.id == playingId && playbackState != PlaybackEngine.State.IDLE
        if (active) holder.bindStateText(entry)
    }

    inner class Holder(private val binding: ItemScriptCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ScriptRepository.Entry) {
            val context = binding.root.context
            binding.textScriptName.text = entry.name
            if (entry.broken) {
                // 只有删除可用：激活/编辑/导出都要先读文件，读不出必然失败
                binding.textScriptLoops.text = context.getString(R.string.script_broken_chip)
                binding.textScriptSteps.text = ""
                binding.textScriptModified.text = context.getString(R.string.script_broken_hint)
                binding.btnCardActivate.alpha = DISABLED_ALPHA
                binding.btnCardEdit.alpha = DISABLED_ALPHA
                binding.btnCardExport.alpha = DISABLED_ALPHA
            } else {
                binding.textScriptLoops.text = if (entry.loopTimes <= 0) {
                    context.getString(R.string.script_loops_infinite)
                } else {
                    context.getString(R.string.script_loops_format, entry.loopTimes)
                }
                binding.textScriptSteps.text =
                    context.getString(R.string.script_steps_format, entry.stepCount)
                binding.textScriptModified.text = context.getString(
                    R.string.script_modified_format,
                    ScriptRepository.formatTime(entry.modifiedAt),
                )
                binding.btnCardActivate.alpha = 1f
                binding.btnCardEdit.alpha = 1f
                binding.btnCardExport.alpha = 1f
            }

            val active = entry.id == playingId && playbackState != PlaybackEngine.State.IDLE
            binding.root.setBackgroundResource(
                if (active) R.drawable.bg_card_active else R.drawable.bg_card,
            )

            if (!active) {
                binding.textScriptState.visibility = View.GONE
                binding.imgStatus.visibility = View.GONE
            } else {
                val color = when {
                    hasError -> R.color.status_error
                    playbackState == PlaybackEngine.State.PAUSED -> R.color.status_paused
                    else -> R.color.status_running
                }
                binding.textScriptState.visibility = View.VISIBLE
                binding.imgStatus.visibility = View.VISIBLE
                binding.textScriptState.setTextColor(ContextCompat.getColor(context, color))
                androidx.core.view.ViewCompat.setBackgroundTintList(
                    binding.imgStatus,
                    ContextCompat.getColorStateList(context, color),
                )
                binding.textScriptState.text = stateText(context)
            }

            // 整卡不再当激活热区：四键已全部露出，误触卡片就弹小窗是纯粹的惊吓
            binding.btnCardActivate.setOnClickListener { onActivate(entry) }
            binding.btnCardEdit.setOnClickListener { onEdit(entry) }
            binding.btnCardExport.setOnClickListener { onExport(entry) }
            binding.btnCardDelete.setOnClickListener { onDelete(entry) }
        }

        /** 只刷新状态文案（payload 路径：巡数变了，颜色与背景不变）。 */
        fun bindStateText(entry: ScriptRepository.Entry) {
            val active = entry.id == playingId && playbackState != PlaybackEngine.State.IDLE
            if (!active) return
            binding.textScriptState.text = stateText(binding.root.context)
        }

        private fun stateText(context: Context): String = when {
            hasError -> context.getString(R.string.status_error_label)
            playbackState == PlaybackEngine.State.PAUSED ->
                context.getString(R.string.status_paused_label)
            // 播放中：状态文案升级成「第 N 巡 / 共 M 次」，比单纯「播放中」信息量大
            else -> progressText(context)
        }

        /** 播放进度：第 N 巡 / 共 M 次（0 次按无限显示 ∞）。 */
        private fun progressText(context: Context): String {
            val limit = PlaybackController.loopTimes
            val cycle = PlaybackController.cursorCycle() + 1L
            val total = if (limit <= 0) {
                context.getString(R.string.editor_loop_infinite)
            } else {
                limit.toString()
            }
            return context.getString(R.string.editor_progress_format, cycle, total)
        }
    }

    private companion object {
        /** 巡数变化的 payload：RecyclerView 因此复用 ViewHolder，不跑 change 动画。 */
        const val PAYLOAD_PROGRESS = "progress"

        /** 损坏条目上不可用按钮的透明度。 */
        const val DISABLED_ALPHA = 0.35f
    }
}

package com.auto.fairy

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.auto.fairy.core.runtime.PlaybackEngine
import com.auto.fairy.databinding.ItemOverlayScriptRowBinding

/**
 * 浮层脚本列表适配器：状态点 + 名称 + 循环·步数 + 激活/编辑/更多。
 *
 * 数据全部来自 [ScriptRepository.list]，不依赖 ViewModel/LiveData。
 */
class OverlayScriptAdapter(
    private val onActivate: (ScriptRepository.Entry) -> Unit,
    private val onEdit: (ScriptRepository.Entry) -> Unit,
    private val onMore: (ScriptRepository.Entry, View) -> Unit,
    private val onBroken: (ScriptRepository.Entry, View) -> Unit,
) : RecyclerView.Adapter<OverlayScriptAdapter.Holder>() {

    private val entries = ArrayList<ScriptRepository.Entry>()
    private var playingId: String? = null
    private var state = PlaybackEngine.State.IDLE
    private var hasError = false

    class Holder(val binding: ItemOverlayScriptRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemOverlayScriptRowBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        val binding = holder.binding
        val context = binding.root.context

        // 状态点只在「当前脚本」上出现：运行绿 / 暂停橙 / 出错红
        val mine = entry.id == playingId
        val showDot = mine && (state != PlaybackEngine.State.IDLE || hasError)
        binding.rowStatus.visibility = if (showDot) View.VISIBLE else View.GONE
        if (showDot) {
            val colorRes = when {
                hasError -> R.color.status_error
                state == PlaybackEngine.State.PAUSED -> R.color.status_paused
                else -> R.color.status_running
            }
            ViewCompat.setBackgroundTintList(
                binding.rowStatus,
                ColorStateList.valueOf(ContextCompat.getColor(context, colorRes)),
            )
        }

        if (entry.broken) {
            // 损坏的文件读不出名字，只能显示落盘 id；激活/编辑没有意义（load 必然失败），
            // 只留「更多 → 删除」。整行留在这里，用户才知道有东西要处理。
            binding.rowName.text = entry.name
            binding.rowMeta.text = context.getString(R.string.script_broken_hint)
            binding.rowActivate.alpha = DISABLED_ALPHA
            binding.rowEdit.alpha = DISABLED_ALPHA
            binding.rowActivate.setOnClickListener {
                onBroken(entry, binding.rowActivate)
            }
            binding.rowEdit.setOnClickListener { onBroken(entry, binding.rowEdit) }
        } else {
            binding.rowName.text = entry.name
            binding.rowMeta.text = if (entry.loopTimes <= 0) {
                context.getString(R.string.overlay_meta_infinite, entry.stepCount)
            } else {
                context.getString(R.string.overlay_meta_loop, entry.loopTimes, entry.stepCount)
            }
            binding.rowActivate.alpha = 1f
            binding.rowEdit.alpha = 1f
            binding.rowActivate.setOnClickListener { onActivate(entry) }
            binding.rowEdit.setOnClickListener { onEdit(entry) }
        }
        binding.rowMore.setOnClickListener { onMore(entry, it) }
        binding.rowCard.setOnClickListener { if (!entry.broken) onEdit(entry) }
    }

    fun submit(
        list: List<ScriptRepository.Entry>,
        playingId: String?,
        state: PlaybackEngine.State,
        hasError: Boolean,
    ) {
        entries.clear()
        entries.addAll(list)
        this.playingId = playingId
        this.state = state
        this.hasError = hasError
        notifyDataSetChanged()
    }

    private companion object {
        /** 损坏条目上「不可用」按钮的透明度。 */
        const val DISABLED_ALPHA = 0.35f
    }
}

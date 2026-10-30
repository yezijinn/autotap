package com.auto.fairy.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.auto.fairy.R
import com.auto.fairy.databinding.ItemEditorEmptyBinding
import com.auto.fairy.databinding.ItemEditorHeaderBinding

/**
 * 编辑器列表头部：脚本级循环次数、默认动作延迟与屏幕分辨率。
 *
 * 头部作为 RecyclerView 的条目（而不是 ScrollView 的固定子 View），这样整页只有一个
 * 滚动容器，既不会有嵌套滚动裁切，也不会因头部太高挤压步骤列表。
 *
 * 默认动作延迟是输入框：每次输入直接回传给 Activity（不回写控件），
 * 否则用户打字到一半就会被 setText 重置光标。
 */
class EditorHeaderAdapter(
    private val onLoopTimesChanged: (Int) -> Unit,
    private val onDefaultDelayChanged: (Long) -> Unit,
) : RecyclerView.Adapter<EditorHeaderAdapter.Holder>() {

    private var loopTimes = 0
    private var defaultDelayMs = 0L
    private var screen: Pair<Int, Int> = 0 to 0
    private var recordedScreen: Pair<Int, Int>? = null

    fun submit(
        times: Int,
        defaultDelayMs: Long,
        screen: Pair<Int, Int>,
        recordedScreen: Pair<Int, Int>? = null,
    ) {
        this.loopTimes = times
        this.defaultDelayMs = defaultDelayMs
        this.screen = screen
        this.recordedScreen = recordedScreen
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemEditorHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind()
    }

    inner class Holder(private val binding: ItemEditorHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** bind 期间写入输入框不应被当成用户编辑。 */
        private var mutating = false

        init {
            binding.editLoopTimes.doAfterTextChanged {
                if (mutating) return@doAfterTextChanged
                onLoopTimesChanged(parseLoopTimes(it?.toString()))
            }
            binding.editDefaultDelay.doAfterTextChanged {
                if (mutating) return@doAfterTextChanged
                onDefaultDelayChanged(parseDelay(it?.toString()))
            }
        }

        fun bind() {
            mutating = true
            binding.editLoopTimes.setText(loopTimes.toString())
            // 分辨率取当前设备（坐标换算的基准）；与脚本记录的不同时补一句提示
            val recorded = recordedScreen
            binding.textHeaderScreen.text = if (recorded == null) {
                binding.root.context.getString(
                    R.string.editor_screen_format,
                    screen.first,
                    screen.second,
                )
            } else {
                binding.root.context.getString(
                    R.string.editor_screen_recorded_format,
                    screen.first,
                    screen.second,
                    recorded.first,
                    recorded.second,
                )
            }
            binding.editDefaultDelay.setText(defaultDelayMs.toString())
            mutating = false
        }

        /** 空输入按 0 处理（= 无延迟），与提示文案一致。 */
        private fun parseDelay(text: String?): Long =
            text?.trim()?.toLongOrNull()?.coerceIn(0L, MAX_DELAY_MS) ?: 0L
    }

    private companion object {
        const val MAX_DELAY_MS = 60_000L
    }
}

/** 空态条目：没有步骤时才占一行。 */
class EditorEmptyAdapter : RecyclerView.Adapter<EditorEmptyAdapter.Holder>() {

    private var visible = false

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemEditorEmptyBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit

    class Holder(binding: ItemEditorEmptyBinding) : RecyclerView.ViewHolder(binding.root)
}

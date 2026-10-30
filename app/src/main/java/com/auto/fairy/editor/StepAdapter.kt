package com.auto.fairy.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.auto.fairy.core.model.Step
import com.auto.fairy.databinding.ItemStepCardBinding

/**
 * 步骤卡片适配器。
 *
 * 编辑器顶层与循环体共用：顶层给上移/下移，循环体只保留编辑 + 删除；
 * 序号由 [labelOf] 提供，保证与坐标可视化上的标签一致。
 */
class StepAdapter(
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onMoveUp: ((Int) -> Unit)? = null,
    private val onMoveDown: ((Int) -> Unit)? = null,
    private val labelOf: (Int) -> String = { (it + 1).toString() },
    private val screen: () -> Pair<Int, Int>,
) : RecyclerView.Adapter<StepAdapter.Holder>() {

    private val items = ArrayList<Step>()

    fun submit(steps: List<Step>) {
        items.clear()
        items.addAll(steps)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemStepCardBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(position, items[position])
    }

    inner class Holder(private val binding: ItemStepCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int, step: Step) {
            val context = binding.root.context
            binding.textStepIndex.text = labelOf(position)
            val (title, sub) = StepSummary.summaryOf(context, step, screen())
            binding.textStepTitle.text = title
            binding.textStepSub.text = sub

            binding.btnStepUp.visibility = if (onMoveUp == null) View.GONE else View.VISIBLE
            binding.btnStepDown.visibility = if (onMoveDown == null) View.GONE else View.VISIBLE
            val canUp = onMoveUp != null && position > 0
            val canDown = onMoveDown != null && position < items.size - 1
            binding.btnStepUp.isEnabled = canUp
            binding.btnStepUp.alpha = if (canUp) 1f else 0.3f
            binding.btnStepDown.isEnabled = canDown
            binding.btnStepDown.alpha = if (canDown) 1f else 0.3f

            binding.btnStepUp.setOnClickListener { onMoveUp?.invoke(position) }
            binding.btnStepDown.setOnClickListener { onMoveDown?.invoke(position) }
            binding.btnStepDelete.setOnClickListener { onDelete(position) }
            binding.cardStep.setOnClickListener { onEdit(position) }
        }
    }
}

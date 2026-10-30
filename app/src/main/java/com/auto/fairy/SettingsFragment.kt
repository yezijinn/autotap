package com.auto.fairy

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.auto.fairy.databinding.FragmentSettingsBinding
import com.auto.fairy.overlay.CoordinateMarkersController
import com.auto.fairy.overlay.OrdinalStyle
import com.auto.fairy.overlay.OrdinalStyleStore

/**
 * 设置页：音量键快捷、悬浮窗贴边、坐标标记层、坐标可拖、步骤序号字体。
 *
 * 只放用户真正会改的几项，其余一律不做——设置项越少越不容易迷路。
 */
class SettingsFragment : Fragment() {

    private var binding: FragmentSettingsBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = FragmentSettingsBinding.inflate(inflater, container, false)
        binding = view
        return view.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        val context = requireContext()

        binding.switchVolumeKey.isChecked = AppSettings.isVolumeKeyEnabled(context)
        binding.switchVolumeKey.setOnCheckedChangeListener { _, checked ->
            AppSettings.setVolumeKeyEnabled(context, checked)
        }

        binding.switchSnapEdge.isChecked = AppSettings.isSnapEdgeEnabled(context)
        binding.switchSnapEdge.setOnCheckedChangeListener { _, checked ->
            AppSettings.setSnapEdgeEnabled(context, checked)
        }

        binding.switchCoordLayer.isChecked = CoordinateMarkersController.isOn(context)
        binding.switchCoordLayer.setOnCheckedChangeListener { _, checked ->
            CoordinateMarkersController.setOn(context, checked)
        }

        binding.switchCoordMove.isChecked = CoordinateMarkersController.isMoveEnabled(context)
        binding.switchCoordMove.setOnCheckedChangeListener { _, checked ->
            CoordinateMarkersController.setMoveEnabled(context, checked)
        }

        setupOrdinalStyle(binding)

        binding.textVersion.text = getString(
            R.string.settings_version_format,
            BuildConfig.VERSION_NAME,
        )

        setupCheckUpdate(binding)
    }

    // ── 检查更新 ────────────────────────────────────────────

    private enum class UpdateState { Idle, Checking, UpToDate, Available, NetworkError }

    private var updateState = UpdateState.Idle
    private val dimRunnable = Runnable { setUpdateState(UpdateState.Idle) }

    private fun setupCheckUpdate(binding: FragmentSettingsBinding) {
        binding.btnCheckUpdate.setText(R.string.settings_check_update)
        binding.btnCheckUpdate.setOnClickListener {
            if (updateState == UpdateState.Checking) return@setOnClickListener
            setUpdateState(UpdateState.Checking)
            UpdateChecker.checkAsync(BuildConfig.VERSION_CODE) { onUpdateChecked(it) }
        }
    }

    private fun setUpdateState(s: UpdateState) {
        val binding = binding ?: return
        updateState = s
        val checking = s == UpdateState.Checking
        binding.btnCheckUpdate.isEnabled = !checking
        binding.btnCheckUpdate.alpha = if (checking) 0.6f else 1f
        binding.btnCheckUpdate.setText(
            if (checking) R.string.update_checking else R.string.settings_check_update,
        )
        if (checking) binding.btnCheckUpdate.postDelayed(dimRunnable, 3_000L)
    }

    private fun onUpdateChecked(r: UpdateChecker.Result) {
        val binding = binding ?: return
        binding.btnCheckUpdate.removeCallbacks(dimRunnable)
        setUpdateState(UpdateState.Idle)
        val local = getString(R.string.update_local_version, BuildConfig.VERSION_CODE)
        val b = AlertDialog.Builder(requireContext())
        when (r) {
            is UpdateChecker.Result.Available -> b
                .setTitle(R.string.update_title_available)
                .setMessage(local + "\n" + getString(R.string.update_latest_version, r.latest))
                .setNegativeButton(R.string.update_btn_later, null)
                .setPositiveButton(R.string.update_btn_go) { _, _ ->
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(UpdateChecker.releasesUrl(r.latest, r.source)),
                        ),
                    )
                }

            is UpdateChecker.Result.UpToDate -> b
                .setTitle(R.string.update_title_prompt)
                .setMessage(
                    local + "\n" +
                        getString(R.string.update_latest_version, r.latest) + "\n" +
                        getString(R.string.update_uptodate),
                )
                .setPositiveButton(R.string.update_btn_ok, null)

            UpdateChecker.Result.NetworkError -> b
                .setTitle(R.string.update_title_prompt)
                .setMessage(local + "\n" + getString(R.string.update_latest_unreachable))
                .setPositiveButton(R.string.update_btn_ok, null)
        }
        b.show()
    }

    // ── 步骤序号字体 ────────────────────────────────────────

    /**
     * 参数用点选 / 拖动 / 数值输入三种方式：
     * - 颜色用 9 个标准色块（七彩 + 黑白），点一下即换；
     * - 字号用输入框（数值型，不会填错格式）；
     * - 数字左右间距用拖动条，边拖边看预览。
     *
     * 每次改动都重新组装 [OrdinalStyle] → 落盘 → 应用到坐标层 → 刷预览。
     */
    private fun setupOrdinalStyle(binding: FragmentSettingsBinding) {
        val context = requireContext()
        var current = OrdinalStyleStore.load(context)

        binding.previewOrdinal.setStyle(current)

        // 9 个色块：背景即颜色，选中态加粗描边
        val colorViews = listOf(
            binding.swatchOrdinalColor0, binding.swatchOrdinalColor1,
            binding.swatchOrdinalColor2, binding.swatchOrdinalColor3,
            binding.swatchOrdinalColor4, binding.swatchOrdinalColor5,
            binding.swatchOrdinalColor6, binding.swatchOrdinalColor7,
            binding.swatchOrdinalColor8,
        )

        fun markSelected(views: List<View>, value: Int) {
            views.forEachIndexed { index, v ->
                v.isSelected = index < OrdinalStyle.PALETTE.size &&
                    OrdinalStyle.PALETTE[index] == value
            }
        }

        fun apply(next: OrdinalStyle) {
            if (next == current) return
            current = next
            OrdinalStyleStore.save(context, current)
            CoordinateMarkersController.applyOrdinalStyle(context)
            binding.previewOrdinal.setStyle(current)
            markSelected(colorViews, current.color)
        }

        colorViews.forEachIndexed { index, v ->
            v.setOnClickListener {
                apply(current.copy(color = OrdinalStyle.PALETTE[index]))
            }
        }

        // 字号：数值输入，合法才生效
        binding.editOrdinalSize.setText(current.sizeSp.toInt().toString())
        attachNumberInput(binding.editOrdinalSize, current.sizeSp.toInt()) { text: CharSequence? ->
            val v = text?.toString()?.trim()?.toFloatOrNull()
                ?.coerceIn(OrdinalStyle.MIN_SIZE_SP, OrdinalStyle.MAX_SIZE_SP)
                ?: current.sizeSp
            apply(current.copy(sizeSp = v))
        }

        // 数字左右间距：拖动条 0..20dp
        binding.seekOrdinalGap.max = OrdinalStyle.MAX_GAP_DP.toInt()
        binding.seekOrdinalGap.progress = current.gapDp.toInt()
        binding.textOrdinalGapValue.text = getString(
            R.string.settings_ordinal_gap_value,
            current.gapDp.toInt(),
        )
        binding.seekOrdinalGap.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    binding.textOrdinalGapValue.text = getString(
                        R.string.settings_ordinal_gap_value,
                        progress,
                    )
                    apply(current.copy(gapDp = progress.toFloat()))
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            },
        )

        markSelected(colorViews, current.color)

        // 复位默认
        binding.btnOrdinalReset.setOnClickListener {
            OrdinalStyleStore.reset(context)
            current = OrdinalStyleStore.load(context)
            binding.editOrdinalSize.setText(current.sizeSp.toInt().toString())
            binding.seekOrdinalGap.progress = current.gapDp.toInt()
            binding.textOrdinalGapValue.text = getString(
                R.string.settings_ordinal_gap_value,
                current.gapDp.toInt(),
            )
            binding.previewOrdinal.setStyle(current)
            markSelected(colorViews, current.color)
            CoordinateMarkersController.applyOrdinalStyle(context)
        }
    }

    /**
     * 数值输入框：只在「内容是合法数字」时回调，避免用户清空瞬间的抖动。
     * 用 tag 记住当前值，输入框内容变化时比较，避免无限回环。
     */
    private fun attachNumberInput(
        edit: android.widget.EditText,
        initial: Int,
        onChange: (CharSequence?) -> Unit,
    ) {
        edit.tag = initial
        edit.post { edit.setSelection(edit.text?.length ?: 0) }
        edit.doAfterTextChanged { text ->
            val raw = text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) return@doAfterTextChanged
            val parsed = raw.toFloatOrNull() ?: return@doAfterTextChanged
            if (parsed.toInt() == (edit.tag as? Int ?: -1)) return@doAfterTextChanged
            edit.tag = parsed.toInt()
            onChange(text)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

package com.auto.fairy

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.auto.fairy.databinding.FragmentKeepaliveBinding
import java.util.Locale

/**
 * 保活引导页（设计方案 4.3）：权限实时清单 + 厂家设置步骤 + 音量键开关。
 *
 * 文案先讲清楚「无 100% 保活」，各项设置只降低被杀概率；是否真的活着交给诊断页
 * 的自检给出实测结论。这里不做任何自我标榜的「已优化」判定。
 */
class KeepAliveFragment : Fragment() {

    private var binding: FragmentKeepaliveBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = FragmentKeepaliveBinding.inflate(inflater, container, false)
        binding = view
        return view.root
    }

    override fun onViewCreated(root: View, savedInstanceState: Bundle?) {
        super.onViewCreated(root, savedInstanceState)
        val view = binding ?: return
        val context = view.root.context

        view.btnA11y.setOnClickListener { openA11ySettings() }
        view.btnOverlay.setOnClickListener { openOverlaySettings() }
        view.btnBattery.setOnClickListener { openBatterySettings() }
        view.btnBackground.setOnClickListener { openBackgroundSettings() }

        // 先同步初值再挂监听，避免恢复默认值时的回调被当成用户操作
        view.switchVolumeKey.isChecked = AppSettings.isVolumeKeyEnabled(context)
        view.switchVolumeKey.setOnCheckedChangeListener { _, checked ->
            AppSettings.setVolumeKeyEnabled(context, checked)
            if (checked && AppSettings.shouldShowVolumeKeyHint(context)) {
                AppSettings.markVolumeKeyHintShown(context)
                Toast.makeText(context, R.string.volume_key_hint, Toast.LENGTH_LONG).show()
            }
        }

        bindRomGuide(view)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // ── 权限清单 ────────────────────────────────────────────

    private fun refresh() {
        val view = binding ?: return
        val context = view.root.context

        setItem(
            view.textA11yIcon,
            view.textA11yStatus,
            PermissionStatus.isA11yEnabled(context),
            R.string.keepalive_on,
            R.string.keepalive_off,
        )
        setItem(
            view.textOverlayIcon,
            view.textOverlayStatus,
            PermissionStatus.isOverlayEnabled(context),
            R.string.keepalive_on,
            R.string.keepalive_off,
        )
        setItem(
            view.textBatteryIcon,
            view.textBatteryStatus,
            PermissionStatus.isIgnoringBatteryOptimizations(context),
            R.string.keepalive_battery_on,
            R.string.keepalive_battery_off,
        )
        val restricted = PermissionStatus.isBackgroundRestricted(context)
        setItem(
            view.textBackgroundIcon,
            view.textBackgroundStatus,
            restricted?.let { !it },
            R.string.keepalive_background_on,
            R.string.keepalive_background_off,
        )
    }

    /** [ok] 为 null 表示系统没给查询接口，按「无法读取」显示，不猜。 */
    private fun setItem(
        icon: TextView,
        status: TextView,
        ok: Boolean?,
        onText: Int,
        offText: Int,
    ) {
        val context = icon.context
        when (ok) {
            true -> {
                icon.text = "✓"
                icon.setTextColor(ContextCompat.getColor(context, R.color.status_running))
                status.setText(onText)
                status.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            false -> {
                icon.text = "!"
                icon.setTextColor(ContextCompat.getColor(context, R.color.status_paused))
                status.setText(offText)
                status.setTextColor(ContextCompat.getColor(context, R.color.status_paused))
            }

            null -> {
                icon.text = "?"
                icon.setTextColor(ContextCompat.getColor(context, R.color.status_stopped))
                status.setText(R.string.keepalive_unknown)
                status.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
        }
    }

    // ── 系统设置跳转 ────────────────────────────────────────

    private fun openA11ySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (!startOrToast(intent)) startOrToast(Intent(Settings.ACTION_SETTINGS))
    }

    private fun openOverlaySettings() {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${requireContext().packageName}"),
        )
        if (!startOrToast(withPackage)) {
            startOrToast(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openBatterySettings() {
        val context = requireContext()
        val ignoring = PermissionStatus.isIgnoringBatteryOptimizations(context) == true
        if (ignoring) {
            // 已在白名单：打开列表让用户自行撤销，而不是反复弹授权框
            startOrToast(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        if (!startOrToast(request)) {
            startOrToast(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /**
     * 后台限制页在 AOSP 与国产 ROM 上的 action 支持度差异很大，
     * 因此先试专用页，失败再退到本应用详情页（详情页始终存在）。
     */
    private fun openBackgroundSettings() {
        val specific = Intent(ACTION_IGNORE_BACKGROUND_RESTRICTIONS)
        if (!startOrToast(specific)) openAppDetails()
    }

    private fun openAppDetails() {
        startOrToast(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${requireContext().packageName}"),
            ),
        )
    }

    private fun startOrToast(intent: Intent): Boolean {
        val context = context ?: return false
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    // ── 厂家步骤 ────────────────────────────────────────────

    private fun bindRomGuide(view: FragmentKeepaliveBinding) {
        val context = view.root.context
        val rom = romOf()
        view.textRomTitle.text = context.getString(
            R.string.keepalive_rom_title_format,
            context.getString(rom.nameRes),
        )
        view.textRomSteps.setText(rom.stepsRes)
    }

    private data class RomGuide(val nameRes: Int, val stepsRes: Int)

    private fun romOf(): RomGuide {
        val raw = (
            android.os.Build.MANUFACTURER + " " +
                android.os.Build.BRAND + " " +
                android.os.Build.MODEL
            ).lowercase(Locale.US)

        fun has(vararg keys: String) = keys.any { raw.contains(it) }

        return when {
            has("xiaomi", "redmi", "poco") ->
                RomGuide(R.string.keepalive_rom_xiaomi, R.string.keepalive_steps_xiaomi)

            has("huawei", "honor") ->
                RomGuide(R.string.keepalive_rom_huawei, R.string.keepalive_steps_huawei)

            has("oppo", "oneplus", "realme") ->
                RomGuide(R.string.keepalive_rom_oppo, R.string.keepalive_steps_oppo)

            has("vivo", "iqoo") ->
                RomGuide(R.string.keepalive_rom_vivo, R.string.keepalive_steps_vivo)

            has("samsung") ->
                RomGuide(R.string.keepalive_rom_samsung, R.string.keepalive_steps_samsung)

            else -> RomGuide(R.string.keepalive_rom_generic, R.string.keepalive_steps_generic)
        }
    }

    companion object {
        /**
         * AOSP 的后台限制页 action；部分 ROM 不存在该页面，调用时必须准备回退。
         * 用字面量而不是 Settings 常量，避免不同 compileSdk 下的可见性差异。
         */
        private const val ACTION_IGNORE_BACKGROUND_RESTRICTIONS =
            "android.settings.IGNORE_BACKGROUND_RESTRICTIONS_SETTINGS"
    }
}

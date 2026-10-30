package com.auto.fairy

import android.content.Context

/**
 * 应用级开关与诊断状态（与脚本偏好分开存放）。
 *
 * 只做键值读写，不持有 Context；所有调用点传 applicationContext 即可。
 */
object AppSettings {

    private const val PREFS = "app_settings"

    private const val KEY_VOLUME_KEY = "volume_key_enabled"
    private const val KEY_VOLUME_HINT = "volume_key_hint_shown"
    private const val KEY_NOTIF_ASKED = "notification_permission_asked"
    private const val KEY_SNAP_EDGE = "snap_edge_enabled"

    private const val KEY_CHECK_TOKEN = "selfcheck_process_token"
    private const val KEY_CHECK_ELAPSED = "selfcheck_elapsed"
    private const val KEY_CHECK_WALL = "selfcheck_wall"
    private const val KEY_CHECK_PLAYING = "selfcheck_was_playing"
    private const val KEY_CHECK_LAST = "selfcheck_last_result"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 音量键 ──────────────────────────────────────────────

    /** 默认开启：多数用户是「录好脚本按音量键开跑」的用法。 */
    fun isVolumeKeyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_KEY, true)

    fun setVolumeKeyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_KEY, enabled).apply()
    }

    /** 悬浮窗松手后贴边；默认关闭——小窗可自由拖到任意位置。 */
    fun isSnapEdgeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SNAP_EDGE, false)

    fun setSnapEdgeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SNAP_EDGE, enabled).apply()
    }

    /** 首次提示只弹一次；用户主动开启时也会弹，由调用方决定。 */
    fun shouldShowVolumeKeyHint(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_VOLUME_HINT, false)

    fun markVolumeKeyHintShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_VOLUME_HINT, true).apply()
    }

    /**
     * 通知权限是否已经问过一次（无论结果）。
     *
     * 只问一次：权限弹窗是阻塞式系统 UI，每次启动都问会被用户当成骚扰；
     * 而本应用不是通知类应用，要它只是为了「后台吐司不被系统丢弃」。
     */
    fun hasAskedNotificationPermission(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_ASKED, false)

    fun markNotificationPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
    }

    // ── 保活自检 ────────────────────────────────────────────

    data class SelfCheck(
        val processToken: String,
        val elapsedMs: Long,
        val wallClock: Long,
        val wasPlaying: Boolean,
    )

    fun beginSelfCheck(context: Context, wasPlaying: Boolean) {
        prefs(context).edit()
            .putString(KEY_CHECK_TOKEN, ProcessIdentity.token)
            .putLong(KEY_CHECK_ELAPSED, android.os.SystemClock.elapsedRealtime())
            .putLong(KEY_CHECK_WALL, System.currentTimeMillis())
            .putBoolean(KEY_CHECK_PLAYING, wasPlaying)
            .apply()
    }

    /** 未完成的自检；返回 null 表示没有进行中的自检。 */
    fun pendingSelfCheck(context: Context): SelfCheck? {
        val store = prefs(context)
        val token = store.getString(KEY_CHECK_TOKEN, null) ?: return null
        return SelfCheck(
            processToken = token,
            elapsedMs = store.getLong(KEY_CHECK_ELAPSED, 0L),
            wallClock = store.getLong(KEY_CHECK_WALL, 0L),
            wasPlaying = store.getBoolean(KEY_CHECK_PLAYING, false),
        )
    }

    fun finishSelfCheck(context: Context, result: String) {
        prefs(context).edit()
            .remove(KEY_CHECK_TOKEN)
            .putString(KEY_CHECK_LAST, result)
            .apply()
    }

    fun lastSelfCheckResult(context: Context): String? =
        prefs(context).getString(KEY_CHECK_LAST, null)
}

/**
 * 本次进程的身份标识。
 *
 * 进程被系统回收后重启时，静态字段会重新初始化，因此与自检开始时写入的值不一致
 * 就是「进程曾被回收」的硬证据——比只看 SharedPreferences 时间戳可靠。
 */
object ProcessIdentity {
    val token: String = java.util.UUID.randomUUID().toString()
}

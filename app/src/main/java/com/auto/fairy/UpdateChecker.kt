package com.auto.fairy

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 统一检查更新：Gitee 优先（国内可直连），GitHub 备选。
 *
 * 发布标签必须是纯数字 YYYYMMDD，解析器只认纯数字，不剥离 v 前缀。
 */
object UpdateChecker {
    private const val OWNER = "yezijinn"
    private const val REPO = "autotap"
    private const val UA = "autotap-update-check"
    private const val TIMEOUT_MS = 10_000

    sealed interface Result {
        data class UpToDate(val latest: Int, val source: String) : Result
        data class Available(val latest: Int, val source: String) : Result
        data object NetworkError : Result
    }

    /** 后台线程检查，结果回主线程；onDone 必被调用一次。 */
    fun checkAsync(local: Int, onDone: (Result) -> Unit) {
        Thread {
            val r = runCatching { fetchLatestDateTag() }.fold(
                onSuccess = { (latest, src) ->
                    if (latest > local) Result.Available(latest, src)
                    else Result.UpToDate(latest, src)
                },
                onFailure = { Result.NetworkError },
            )
            Handler(Looper.getMainLooper()).post { onDone(r) }
        }.apply { name = "update-check" }.start()
    }

    fun releasesUrl(latest: Int, source: String): String =
        if (source == "gitee") "https://gitee.com/$OWNER/$REPO/releases/tag/$latest"
        else "https://github.com/$OWNER/$REPO/releases"

    private fun fetchLatestDateTag(): Pair<Int, String> {
        // Gitee 优先：国内网络可直连，GitHub 常不可达
        fetchFromGitee()?.let { return it to "gitee" }
        fetchFromGithub()?.let { return it to "github" }
        throw IOException("all update sources unreachable")
    }

    private fun fetchFromGithub(): Int? = runCatching {
        val html = httpGet("https://github.com/$OWNER/$REPO/tags") ?: return@runCatching null
        Regex("""$OWNER/$REPO/(?:tree|releases/tag)/([^"'<>?#\s]+)""", RegexOption.IGNORE_CASE)
            .findAll(html).mapNotNull { normalizeTag(it.groupValues[1]) }.maxOrNull()
    }.getOrNull()

    /** Gitee 网页 /tags 是 405，必须走开放 API；归一化规则与 GitHub 一致。 */
    private fun fetchFromGitee(): Int? = runCatching {
        val body = httpGet("https://gitee.com/api/v5/repos/$OWNER/$REPO/tags")
            ?: return@runCatching null
        Regex(""""name"\s*:\s*"([^"]+)"""")
            .findAll(body).mapNotNull { normalizeTag(it.groupValues[1]) }.maxOrNull()
    }.getOrNull()

    /** 发布标签必须是纯数字 YYYYMMDD；带 v 前缀等违规写法一律不接受。 */
    private fun normalizeTag(raw: String): Int? =
        raw.takeIf { it.length in 6..8 && it.all(Char::isDigit) }?.toIntOrNull()

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

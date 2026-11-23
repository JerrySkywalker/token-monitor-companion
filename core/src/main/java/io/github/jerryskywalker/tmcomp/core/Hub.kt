package io.github.jerryskywalker.tmcomp.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class HubException(val status: Int) : IOException(
    when (status) {
        401, 403 -> "Hub 拒绝授权，请核对 Secret"
        404 -> "该地址没有 Token Monitor Hub API"
        else -> "Hub 返回 HTTP $status"
    }
)

fun origin(input: String, allowInsecure: Boolean): String {
    val uri = runCatching { URI(input.trim().trimEnd('/')) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    val local = uri?.host in setOf("10.0.2.2", "127.0.0.1", "localhost", "10.0.2.16")
    require(scheme == "https" || (allowInsecure && scheme == "http" && local)) { "请输入 Hub 的 HTTPS 地址" }
    require(uri != null && uri.host != null && uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() &&
        uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
        "Hub 地址只能包含主机和端口"
    }
    return scheme + "://" + uri.rawAuthority.lowercase()
}

@Serializable
class HubConnection(val origin: String, val secret: String) {
    override fun toString() = "HubConnection(origin=$origin)"
}

fun hubConnection(address: String, secret: String, allowInsecure: Boolean): HubConnection {
    val clean = secret.trim()
    require(clean.isNotEmpty() && clean.length <= 2048 && clean.all { it.code in 33..126 }) { "请输入有效的 Hub Secret" }
    return HubConnection(origin(address, allowInsecure), clean)
}

class Hub(private val connection: HubConnection) {
    suspend fun refreshWhileVisible(fallbackMillis: Long, refresh: suspend () -> Unit) =
        connection.refreshWhileVisible(fallbackMillis, refresh)

    suspend fun subscriptions(): HubSubscriptions = withContext(Dispatchers.IO) {
        request("/api/subscriptions", 4_000, 1024 * 1024).useJson<HubSubscriptions>().also {
            if (!it.ok) throw IOException("无法读取 Hub 订阅")
        }
    }

    suspend fun snapshot(previous: Snapshot? = null, extendedTrends: Boolean = false): Snapshot = withContext(Dispatchers.IO) {
        val stats = request("/api/stats", 12_000, 16 * 1024 * 1024).useJson<HubStats>()
        val now = Instant.now()
        val days = if (extendedTrends) 366 else 30
        val due = previous?.historyCheckedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.isAfter(now.minusSeconds(15 * 60)) != true
        val fetchHistory = stats.historyPreview?.daily.orEmpty().size < days && (due || !extendedTrends)
        val history = if (fetchHistory) {
            try {
                request("/api/history", 4_000, 16 * 1024 * 1024).useJson<HubHistory>()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else null
        val next = projectHub(stats, history, now, previous?.history.orEmpty(), days)
        if (extendedTrends) next.copy(
            trendSamples = captureTrend(next, previous?.trendSamples.orEmpty(), now),
            historyCheckedAt = if (fetchHistory) now.toString() else previous?.historyCheckedAt,
        ) else next
    }

    private fun request(path: String, timeout: Int, maxBytes: Int): String {
        val http = URI(connection.origin + path).toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod = "GET"
            http.connectTimeout = timeout
            http.readTimeout = timeout
            http.instanceFollowRedirects = false
            http.setRequestProperty("Accept", "application/json")
            http.setRequestProperty("Authorization", "Bearer " + connection.secret)
            val status = http.responseCode
            if (status !in 200..299) throw HubException(status)
            return http.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size == -1) break
                    if (output.size() + size > maxBytes) throw IOException("Hub 响应过大")
                    output.write(buffer, 0, size)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            http.disconnect()
        }
    }

    private inline fun <reified T> String.useJson(): T =
        runCatching { wireJson.decodeFromString<T>(this) }.getOrElse { throw IOException("无法读取 Hub 数据格式") }
}

private fun captureTrend(snapshot: Snapshot, previous: List<TrendSample>, now: Instant): List<TrendSample> {
    val time = now.toEpochMilli()
    val recent = previous.filter { it.at in (time - 27 * 60 * 60_000L) until time }
    if (snapshot.stale || (snapshot.today.tokens == null && snapshot.today.costUsd == null)) return recent
    // Retain at most one observation every five minutes, within the latest wall-clock day.
    val slot = time / 300_000
    fun Period.counter(maxTools: Int) = TrendCounter(tokens, costUsd, tools.take(maxTools).map { it.copy(costUsd = null) })
    val sample = TrendSample(time, snapshot.today.counter(40),
        snapshot.collectors.filterNot { it.stale }.associate { it.key to it.today.counter(12) })
    return (recent.filter { it.at / 300_000 < slot } + sample).takeLast(325)
}

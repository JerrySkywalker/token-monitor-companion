package io.github.jerryskywalker.tmcomp.core

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class GatewayException(val status: Int) : IOException(
    when (status) {
        401 -> "授权失效或配对码无效"
        403 -> "设备权限不足"
        404 -> "服务器上没有该手表"
        409 -> "服务器状态已变化，请刷新"
        503 -> "Token Monitor Hub 暂不可用"
        else -> "服务器返回 HTTP " + status
    }
)

fun origin(input: String, allowInsecure: Boolean): String {
    val candidate = input.trim().trimEnd('/')
    val uri = URI(candidate)
    val scheme = uri.scheme?.lowercase()
    val debugHost = uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost", "10.0.2.16")
    require(scheme == "https" || (allowInsecure && scheme == "http" && debugHost)) { "请输入服务器 HTTPS 地址" }
    require(uri.host != null && uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() &&
        uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) { "服务器地址只能包含主机和端口" }
    return scheme + "://" + uri.rawAuthority.lowercase()
}

fun pairingCode(code: String): String {
    val value = code.replace(" ", "").replace("-", "").uppercase()
    require(value.matches(Regex("[A-F0-9]{18}"))) { "配对码应为 18 位十六进制字符" }
    return value
}

fun scanPairing(value: String, allowInsecure: Boolean): PairCode {
    val uri = Uri.parse(value)
    require(uri.scheme == "companion" && uri.host == "pair" && uri.getQueryParameter("v") == "1" &&
        uri.getQueryParameter("kind") == "phone") { "这不是手机配对二维码" }
    return PairCode(origin(uri.getQueryParameter("url").orEmpty(), allowInsecure), pairingCode(uri.getQueryParameter("code").orEmpty()))
}

class Gateway(private val base: String, private val token: String? = null) {
    private suspend inline fun <reified T> get(path: String): T = wireJson.decodeFromString(request("GET", path))
    private suspend inline fun <reified B, reified T> put(path: String, body: B): T =
        wireJson.decodeFromString(request("PUT", path, wireJson.encodeToString(body)))

    suspend fun enroll(code: String, kind: String, label: String, id: String? = null): Credentials {
        val payload = wireJson.encodeToString(Enrollment(pairingCode(code), kind, label, id))
        val result = wireJson.decodeFromString<EnrollmentResult>(request("POST", "/v1/pair/consume", payload))
        require(result.kind == kind && (kind != "wear" || result.watchId == id)) { "服务器返回的设备身份不匹配" }
        return Credentials(base, result.token)
    }

    suspend fun usage() = get<Snapshot>("/v1/usage")
    suspend fun watches() = get<List<WatchConfig>>("/v1/watches")
    suspend fun register(id: String, label: String) = put<Registration, WatchConfig>("/v1/watches/" + id, Registration(label))
    suspend fun update(id: String, config: ConfigEdit) = put<ConfigEdit, WatchConfig>("/v1/watches/" + id + "/config", config)
    suspend fun invite(id: String): PairCode = wireJson.decodeFromString(request("POST", "/v1/watches/" + id + "/pair", "{}"))
    suspend fun remove(id: String): Removed = wireJson.decodeFromString(request("DELETE", "/v1/watches/" + id))
    suspend fun relayReport(id: String, report: Report) = put<Report, WatchConfig>("/v1/watches/" + id + "/report", report)
    suspend fun watchConfig() = get<WatchConfig>("/v1/watch/config")
    suspend fun watchUsage() = get<Snapshot>("/v1/watch/usage")
    suspend fun watchReport(report: Report) = put<Report, WatchConfig>("/v1/watch/report", report)

    private suspend fun request(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer " + it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw GatewayException(status)
            connection.inputStream.use {
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = it.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) throw IOException("服务器响应过大")
                    output.write(chunk, 0, count)
                }
                output.toString(UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}

fun Credentials.gateway() = Gateway(origin, token)

package io.github.jerryskywalker.tmcomp.mobile

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val COMPANION_GITHUB = "https://github.com/JerrySkywalker/token-monitor-companion"
private const val RELEASE_API = "https://api.github.com/repos/JerrySkywalker/token-monitor-companion/releases/latest"
private val stableVersion = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)")

internal data class ReleaseCheck(val tag: String, val comparison: Int)
internal class ReleaseRateLimited : IOException()

private fun String.versionParts() =
    requireNotNull(stableVersion.matchEntire(this)).groupValues.drop(1).map { it.toInt() }

internal fun compareReleaseVersions(latest: String, current: String): Int {
    val installedParts = current.versionParts()
    return latest.versionParts().zip(installedParts)
        .firstOrNull { (remote, installed) -> remote != installed }
        ?.let { (remote, installed) -> remote.compareTo(installed) } ?: 0
}

internal suspend fun checkLatestRelease(current: String): ReleaseCheck = withContext(Dispatchers.IO) {
    val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Token-Monitor-Companion/$current")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        when (connection.responseCode) {
            403, 429 -> throw ReleaseRateLimited()
            HttpURLConnection.HTTP_OK -> Unit
            else -> throw IOException("GitHub release lookup failed")
        }
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count == -1) break
                if (text.length + count > 256 * 1024) throw IOException("GitHub release response too large")
                text.append(buffer, 0, count)
            }
            text.toString()
        }
        val metadata = JSONObject(body)
        if (metadata.getBoolean("draft") || metadata.getBoolean("prerelease")) {
            throw IOException("Expected a stable release")
        }
        val tag = metadata.getString("tag_name")
        ReleaseCheck(tag, compareReleaseVersions(tag, current))
    } finally {
        connection.disconnect()
    }
}

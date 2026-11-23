package io.github.jerryskywalker.tmcomp.core

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal suspend fun HubConnection.refreshWhileVisible(fallbackMillis: Long, refresh: suspend () -> Unit): Unit = coroutineScope {
    val requests = Channel<Unit>(Channel.CONFLATED)
    launch {
        while (isActive) {
            requests.trySend(Unit)
            delay(fallbackMillis)
        }
    }
    launch { updates().collect { requests.trySend(Unit) } }
    for (request in requests) {
        refresh()
        delay(400)
    }
}

private fun HubConnection.updates() = channelFlow {
    var retry = 1_000L
    while (isActive) {
        try {
            readUpdates {
                retry = 1_000L
                trySend(Unit)
            }
        } catch (http: HubException) {
            // Older Hubs or proxies may serve REST without an event stream.
            if (http.status in setOf(401, 403, 404, 405, 415, 501)) break
        } catch (_: IOException) {
            // The periodic REST fetch continues while the stream reconnects.
        }
        delay(retry)
        retry = (retry * 2).coerceAtMost(60_000L)
    }
}.buffer(Channel.CONFLATED)

private suspend fun HubConnection.readUpdates(signal: () -> Unit) = withContext(Dispatchers.IO) {
    val http = URI("$origin/api/stats/stream").toURL().openConnection() as HttpURLConnection
    http.instanceFollowRedirects = false
    http.connectTimeout = 10_000
    http.readTimeout = 45_000
    http.setRequestProperty("Accept", "text/event-stream")
    http.setRequestProperty("Authorization", "Bearer $secret")
    suspendCancellableCoroutine<Unit> { stream ->
        stream.invokeOnCancellation {
            Dispatchers.IO.dispatch(EmptyCoroutineContext) { http.disconnect() }
        }
        try {
            val status = http.responseCode
            if (status !in 200..299) throw HubException(status)
            if (!http.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream", ignoreCase = true)) {
                throw HubException(415)
            }
            http.inputStream.use { readHubEvents(it, { stream.isActive }, signal) }
            stream.resumeWith(Result.success(Unit))
        } catch (failure: Exception) {
            stream.resumeWith(Result.failure(failure))
        } finally {
            http.disconnect()
        }
    }
}

/** Only the event type and frame boundary are needed. REST owns snapshot decoding and caching. */
internal fun readHubEvents(input: InputStream, active: () -> Boolean, signal: () -> Unit) {
    val buffer = ByteArray(8_192)
    val prefix = StringBuilder(64)
    var kind = ""
    var data = false
    while (active()) {
        val count = input.read(buffer)
        if (count < 0) return
        for (i in 0 until count) {
            val char = (buffer[i].toInt() and 0xff).toChar()
            if (char == '\n') {
                val line = prefix.toString().trimEnd('\r')
                when {
                    line.isEmpty() -> {
                        if (data && (kind.isEmpty() || kind in usageEvents) && active()) signal()
                        kind = ""
                        data = false
                    }
                    line.startsWith("event:") -> kind = line.substringAfter(':').trim()
                    line.startsWith("data:") -> data = true
                }
                prefix.setLength(0)
            } else if (prefix.length < 64) {
                prefix.append(char)
            }
        }
    }
}

private val usageEvents = setOf("snapshot", "stats", "delta", "freshness")

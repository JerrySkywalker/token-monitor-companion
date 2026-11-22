package io.github.jerryskywalker.tmcomp.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val QR_PREFIX = "tmcomp:1:"
private const val QR_LENGTH = 51
private const val MAX_FRAME = 32768
private const val NONCE_BYTES = 12
private const val KEY_BYTES = 32
private const val TAG_BYTES = 16
private const val CHALLENGE_BYTES = 16
private const val LIFETIME_MS = 120_000L
private val random = SecureRandom()

class WatchPairingCode internal constructor(
    internal val address: Inet4Address,
    internal val port: Int,
    internal val key: ByteArray,
) {
    fun encode(): String {
        val bytes = ByteBuffer.allocate(4 + 2 + KEY_BYTES)
            .put(address.address).putShort(port.toShort()).put(key).array()
        return QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        fun parse(text: String): WatchPairingCode {
            val code = runCatching {
                require(text.startsWith(QR_PREFIX) && text.length == QR_PREFIX.length + QR_LENGTH)
                val encoded = text.substring(QR_PREFIX.length)
                val bytes = Base64.getUrlDecoder().decode(encoded)
                require(bytes.size == 4 + 2 + KEY_BYTES)
                val buffer = ByteBuffer.wrap(bytes)
                val address = InetAddress.getByAddress(ByteArray(4).also { buffer.get(it) }) as Inet4Address
                val port = buffer.short.toInt() and 0xffff
                require(address.isSiteLocalAddress && port >= 1024)
                WatchPairingCode(address, port, ByteArray(KEY_BYTES).also { buffer.get(it) })
            }.getOrNull()
            return requireNotNull(code) { "这不是有效的 Token Monitor 手表配对码" }
        }
    }
}

/** The optical code authenticates a single import. Every TCP connection gets a new challenge. */
internal class PairingCipher(key: ByteArray, private val challenge: ByteArray) {
    private val key = SecretKeySpec(key, "AES")

    private fun cipher(mode: Int, nonce: ByteArray, response: Boolean) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(if (response) "tmcomp/lan/v1/response".encodeToByteArray() else "tmcomp/lan/v1/request".encodeToByteArray())
            updateAAD(challenge)
        }

    fun encrypt(data: ByteArray, response: Boolean): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        return nonce + cipher(Cipher.ENCRYPT_MODE, nonce, response).doFinal(data)
    }

    fun decrypt(frame: ByteArray, response: Boolean): ByteArray {
        require(frame.size in (NONCE_BYTES + TAG_BYTES)..MAX_FRAME)
        val nonce = frame.copyOf(NONCE_BYTES)
        return cipher(Cipher.DECRYPT_MODE, nonce, response).doFinal(frame, NONCE_BYTES, frame.size - NONCE_BYTES)
    }
}

class WatchPairingSession private constructor(
    val code: WatchPairingCode,
    private val server: ServerSocket,
) : Closeable {
    private var client: Socket? = null
    private var closed = false
    private val expires = elapsedMillis() + LIFETIME_MS

    suspend fun receive(applyHub: suspend (HubConnection) -> WatchHubResult): WatchHubResult = receiveTransfer {
        val origin = it.origin
        val secret = it.secret
        if (origin == null || secret == null) WatchHubResult(error = "配对码用于导入 Hub")
        else applyHub(HubConnection(origin, secret))
    }

    suspend fun receiveTransfer(apply: suspend (WatchTransfer) -> WatchHubResult): WatchHubResult = withContext(Dispatchers.IO) {
        while (elapsedMillis() < expires) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use {
                synchronized(this@WatchPairingSession) {
                    if (closed) throw IOException("配对已结束")
                    client = it
                }
                try {
                    val input = DataInputStream(it.getInputStream())
                    val output = DataOutputStream(it.getOutputStream())
                    val challenge = randomBytes(CHALLENGE_BYTES)
                    val channel = PairingCipher(code.key, challenge)
                    val hub = runCatching {
                        output.write(challenge)
                        output.flush()
                        val deadline = minOf(expires, elapsedMillis() + 3_000)
                        val frame = input.readFrame { buffer ->
                            var offset = 0
                            while (offset < buffer.size) {
                                val remaining = deadline - elapsedMillis()
                                if (remaining <= 0) throw SocketTimeoutException()
                                it.soTimeout = remaining.toInt()
                                val count = input.read(buffer, offset, buffer.size - offset)
                                if (count < 0) throw IOException("配对消息不完整")
                                offset += count
                            }
                        }
                        wireJson.decodeFromString<WatchTransfer>(channel.decrypt(frame, false).decodeToString())
                    }.getOrNull() ?: return@use
                    if (elapsedMillis() >= expires) throw IOException("配对码已过期，请重新打开")
                    server.close() // Consume the code before doing the Hub request.
                    val result = apply(hub)
                    // The Hub may already be saved if the phone disconnects before acknowledgement.
                    runCatching { output.writeFrame(channel.encrypt(wireJson.encodeToString(result).encodeToByteArray(), true)) }
                    return@withContext result
                } finally {
                    synchronized(this@WatchPairingSession) { client = null }
                }
            }
        }
        throw IOException("配对码已过期，请重新打开")
    }

    @Synchronized
    override fun close() {
        closed = true
        runCatching { server.close() }
        runCatching { client?.close() }
    }

    companion object {
        fun open(context: Context): WatchPairingSession {
            val (network, manager) = wifiNetwork(context)
            val address = manager.getLinkProperties(network)?.linkAddresses?.asSequence()
                ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull { it.isSiteLocalAddress }
                ?: throw IOException("请先在手表上连接有 IPv4 地址的 Wi‑Fi")
            return open(address)
        }

        internal fun open(address: Inet4Address): WatchPairingSession {
            val server = ServerSocket()
            try {
                server.bind(InetSocketAddress(address, 0), 4)
                server.soTimeout = 500
                return WatchPairingSession(WatchPairingCode(address, server.localPort, randomBytes(KEY_BYTES)), server)
            } catch (failure: Exception) {
                server.close()
                throw failure
            }
        }
    }
}

suspend fun sendWatchHub(context: Context, code: WatchPairingCode, hub: HubConnection): WatchHubResult =
    sendWatchTransfer(context, code, WatchTransfer(hub.origin, hub.secret))

suspend fun sendWatchDisplay(context: Context, code: WatchPairingCode, display: WatchDisplay): WatchHubResult =
    sendWatchTransfer(context, code, WatchTransfer(display = display))

private suspend fun sendWatchTransfer(context: Context, code: WatchPairingCode, request: WatchTransfer): WatchHubResult =
    withContext(Dispatchers.IO) {
        val (network) = wifiNetwork(context)
        try {
            exchangeWatchTransfer(network.socketFactory.createSocket(), code, request)
        } catch (_: java.net.ConnectException) {
            throw IOException("无法连接手表，请确认两端连接同一 Wi‑Fi 并重新扫码")
        } catch (_: SocketTimeoutException) {
            throw IOException("连接手表超时，请确认两端连接同一 Wi‑Fi 并重新扫码")
        }
    }

internal fun exchangeWatchHub(socket: Socket, code: WatchPairingCode, hub: HubConnection): WatchHubResult =
    exchangeWatchTransfer(socket, code, WatchTransfer(hub.origin, hub.secret))

internal fun exchangeWatchTransfer(socket: Socket, code: WatchPairingCode, request: WatchTransfer): WatchHubResult = socket.use {
    it.connect(InetSocketAddress(code.address, code.port), 5_000)
    it.soTimeout = 40_000
    val input = DataInputStream(it.getInputStream())
    val output = DataOutputStream(it.getOutputStream())
    val challenge = ByteArray(CHALLENGE_BYTES).also { input.readFully(it) }
    val channel = PairingCipher(code.key, challenge)
    output.writeFrame(channel.encrypt(wireJson.encodeToString(request).encodeToByteArray(), false))
    runCatching {
        wireJson.decodeFromString<WatchHubResult>(channel.decrypt(input.readFrame(), true).decodeToString())
    }.getOrElse { throw IOException("未收到手表确认，请查看手表后重新扫码") }
}

@Suppress("DEPRECATION") // A cellular or VPN default can coexist with a reachable local Wi-Fi network.
private fun wifiNetwork(context: Context): Pair<Network, ConnectivityManager> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.allNetworks.firstOrNull {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: throw IOException("请连接 Wi‑Fi 后重试")
    return network to manager
}

private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
private fun elapsedMillis() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

private fun DataInputStream.readFrame(read: (ByteArray) -> Unit = { readFully(it) }): ByteArray {
    val header = ByteArray(2).also(read)
    val size = ByteBuffer.wrap(header).short.toInt() and 0xffff
    if (size !in (NONCE_BYTES + TAG_BYTES)..MAX_FRAME) throw IOException("无效的配对消息")
    return ByteArray(size).also(read)
}

private fun DataOutputStream.writeFrame(frame: ByteArray) {
    require(frame.size <= MAX_FRAME)
    writeShort(frame.size)
    write(frame)
    flush()
}

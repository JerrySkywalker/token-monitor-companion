package io.github.jerryskywalker.tmcomp.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LanPairingTest {
    private val key = ByteArray(32) { it.toByte() }
    private val challenge = ByteArray(16) { 42 }

    @Test
    fun pairingCodeRoundTripsAndRejectsPublicAddresses() {
        val address = InetAddress.getByName("192.168.56.42") as Inet4Address
        val code = WatchPairingCode(address, 60233, key)
        val parsed = WatchPairingCode.parse(code.encode())
        assertEquals(address, parsed.address)
        assertEquals(60233, parsed.port)
        assertArrayEquals(key, parsed.key)
        assertThrows(IllegalArgumentException::class.java) { WatchPairingCode.parse("https://example.com") }
        val public = InetAddress.getByName("203.0.113.1") as Inet4Address
        assertThrows(IllegalArgumentException::class.java) { WatchPairingCode.parse(WatchPairingCode(public, 443, key).encode()) }
    }

    @Test
    fun encryptedFramesRequirePairingKeyDirectionAndFreshChallenge() {
        val payload = wireJson.encodeToString(HubConnection("https://hub.example", "fixture-secret")).encodeToByteArray()
        val channel = PairingCipher(key, challenge)
        val frame = channel.encrypt(payload, response = false)
        assertArrayEquals(payload, channel.decrypt(frame, response = false))
        assertThrows(GeneralSecurityException::class.java) { channel.decrypt(frame, response = true) }
        val wrongKey = PairingCipher(ByteArray(32), challenge)
        assertThrows(GeneralSecurityException::class.java) { wrongKey.decrypt(frame, response = false) }
        val freshHandshake = PairingCipher(key, ByteArray(16))
        assertThrows(GeneralSecurityException::class.java) { freshHandshake.decrypt(frame, response = false) }
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { channel.decrypt(frame, response = false) }
    }

    @Test
    fun phoneImportAcknowledgesOneAuthorizedClient() = runBlocking<Unit> {
        val credentials = HubConnection("https://hub.example", "fixture-secret")
        val address = InetAddress.getByName("127.0.0.1") as Inet4Address
        WatchPairingSession.open(address).use { session ->
            val watch = async {
                session.receive {
                    assertEquals(credentials.origin, it.origin)
                    assertEquals(credentials.secret, it.secret)
                    WatchHubResult(origin = it.origin)
                }
            }
            val phone = async(Dispatchers.IO) {
                val wrongCode = WatchPairingCode(address, session.code.port, key)
                assertThrows(IOException::class.java) { exchangeWatchHub(Socket(), wrongCode, credentials) }
                assertEquals(credentials.origin, exchangeWatchHub(Socket(), session.code, credentials).origin)
                assertThrows(IOException::class.java) { exchangeWatchHub(Socket(), session.code, credentials) }
            }
            assertEquals(credentials.origin, watch.await().origin)
            phone.await()
        }
    }

    @Test
    fun transfersDisplaySettingsWithoutSendingHubCredentials() = runBlocking<Unit> {
        val display = WatchDisplay(
            quotaOrders = mapOf(WatchQuotaCategory.CYCLE to listOf("cursor", "codex")),
            trend = WatchTrendSettings(Range.LAST_30, TrendIntervals(daily = TrendInterval.DAYS_7), TrendStyle.CANDLE),
            oled = true,
        )
        val address = InetAddress.getByName("127.0.0.1") as Inet4Address
        WatchPairingSession.open(address).use { session ->
            val watch = async {
                session.receiveTransfer {
                    assertNull(it.origin)
                    assertNull(it.secret)
                    assertEquals(display, it.display)
                    WatchHubResult(displayApplied = true)
                }
            }
            val phone = async(Dispatchers.IO) {
                exchangeWatchTransfer(Socket(), session.code, WatchTransfer(display = display))
            }
            assertTrue(phone.await().displayApplied)
            assertTrue(watch.await().displayApplied)
        }
        val previous = HubConnection("https://hub.example", "fixture-secret")
        val decoded = wireJson.decodeFromString<WatchTransfer>(wireJson.encodeToString(previous))
        assertEquals(previous.origin, decoded.origin)
        assertNull(decoded.display)
    }
}

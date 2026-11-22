package io.github.jerryskywalker.tmcomp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    @Test fun validatesGatewayOrigin() {
        assertEquals("https://companion.example:8443", origin(" https://Companion.Example:8443/ ", false))
        assertEquals("http://10.0.2.2:8787", origin("http://10.0.2.2:8787", true))
        listOf("http://10.0.2.2:8787", "https://user@companion.example", "https://companion.example/api",
            "https://companion.example#test", "https://companion.example?foo=bar").forEach {
            assertThrows(IllegalArgumentException::class.java) { origin(it, false) }
        }
    }

    @Test fun normalizesPairingCode() {
        assertEquals("0123456789ABABCDEF", pairingCode("012345-6789ab-abcdef"))
        assertThrows(IllegalArgumentException::class.java) { pairingCode("0123") }
    }

    @Test fun keepsPinnedBalanceAndUrgentFreshQuotaWithoutDuplicates() {
        val known = listOf(
            Reading(id = "stale", provider = "Codex", label = "Week", kind = "quota", remainingPercent = 3.0, stale = true),
            Reading(id = "fresh", provider = "Codex", label = "Day", kind = "quota", remainingPercent = 37.0),
            Reading(id = "balance", provider = "Zen", label = "USD", kind = "balance", amount = 0.0),
        )
        val snapshot = Snapshot(schema = 1, retrievedAt = "2026-11-22T00:00:00Z", stale = false,
            today = Period(), month = Period(), readings = known)
        assertEquals(listOf("balance", "fresh"), snapshot.forWatch("balance").readings.map { it.id })
        assertEquals(listOf("fresh", "balance"), snapshot.forWatch("missing").readings.map { it.id })
    }
}

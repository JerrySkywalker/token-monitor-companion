package io.github.jerryskywalker.tmcomp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import java.time.Instant
import org.junit.Test

class ProtocolTest {
    @Test fun validatesHubOrigin() {
        assertEquals("https://companion.example:8443", origin(" https://Companion.Example:8443/ ", false))
        assertEquals("http://10.0.2.2:8787", origin("http://10.0.2.2:8787", true))
        listOf("http://10.0.2.2:8787", "https://user@companion.example", "https://companion.example/api",
            "https://companion.example#test", "https://companion.example?foo=bar").forEach {
            assertThrows(IllegalArgumentException::class.java) { origin(it, false) }
        }
    }

    @Test fun normalizesAndRedactsHubSecret() {
        val hub = hubConnection("https://usage.example", "  test-only  ", false)
        assertEquals("test-only", hub.secret)
        assertFalse(hub.toString().contains(hub.secret))
        assertThrows(IllegalArgumentException::class.java) { hubConnection("https://usage.example", "ab\ncd", false) }
    }

    @Test fun projectsHubUsageAndKeepsAccountWindowsAndUnavailableSourcesDistinct() {
        val stats = wireJson.decodeFromString<HubStats>("""
            {
              "updatedAt":"2026-11-22T12:00:00Z",
              "periods":{"today":{"totalTokens":100,"cacheReadTokens":20,"cacheWriteTokens":0,"outputTokens":25,
                "capabilities":{"tokenComponents":true},"clients":{"codex":100},
                "clientModels":{"codex":{"gpt":100}},"ignoredSessions":{"example":"not stored"}}},
              "historyPreview":{"daily":[{"date":"2026-11-22","tokens":100,"cost":0}]},
              "limits":{"updatedAt":"2026-11-22T12:00:00Z","providers":[
                {"provider":"codex","accountKey":"a","status":"ok","source":"oauth","windows":[
                  {"kind":"weekly","usedPercent":15,"resetsAt":"2026-11-29T12:00:00Z","windowMinutes":10080}]},
                {"provider":"codex","accountKey":"b","status":"ok","windows":[{"kind":"weekly","remainingPercent":0}]},
                {"provider":"opencode","status":"ok","balanceUsd":0},
                {"provider":"cursor","status":"unavailable"}
              ]}
            }
        """.trimIndent())
        val now = Instant.parse("2026-11-22T12:01:00Z")
        val projected = projectHub(stats, null, now)
        assertEquals(55L, projected.today.input)
        assertEquals("gpt", projected.today.toolModels["codex"]?.single()?.key)
        assertEquals(30, projected.history.size)
        assertEquals(100L, projected.history.last().tokens)
        assertEquals(listOf(85.0, 0.0, null), projected.readings.map { it.remainingPercent })
        assertEquals(3, projected.readings.distinctBy { it.id }.size)
        assertEquals(0.0, projected.readings.last().amount)
        assertEquals("USD", projected.readings.last().currency)
        assertEquals("unavailable", projected.toolSources.last().status)
        assertFalse(projected.stale)
    }

    @Test fun keepsPinnedBalanceAndUrgentFreshQuotaWithoutDuplicates() {
        val known = listOf(
            Reading(id = "stale", provider = "Codex", label = "Week", kind = "quota", remainingPercent = 3.0, stale = true),
            Reading(id = "fresh", provider = "Codex", label = "Day", kind = "quota", remainingPercent = 37.0),
            Reading(id = "balance", provider = "Zen", label = "USD", kind = "balance", amount = 0.0),
        )
        val snapshot = Snapshot(schema = 1, retrievedAt = "2026-11-22T00:00:00Z", stale = false,
            today = Period(), month = Period(), readings = known)
        assertEquals(listOf("balance", "fresh", "stale"), snapshot.forWatch("balance").readings.map { it.id })
        assertEquals(listOf("fresh", "stale", "balance"), snapshot.forWatch("missing").readings.map { it.id })
    }
}

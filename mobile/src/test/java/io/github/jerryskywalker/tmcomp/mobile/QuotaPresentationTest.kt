package io.github.jerryskywalker.tmcomp.mobile

import io.github.jerryskywalker.tmcomp.core.Reading
import io.github.jerryskywalker.tmcomp.core.ToolSource
import org.junit.Assert.*
import org.junit.Test

class QuotaPresentationTest {
    @Test fun antigravityPairsOnlyWithinPoolAndExpandsArgon() {
        val readings = listOf(
            row("g5", "antigravity", "Gemini 5-hour", "session"),
            row("gw", "antigravity", "Gemini weekly", "weekly"),
            row("aw", "antigravity", "Gemini 4 Argon weekly", "weekly"),
            row("cw", "antigravity", "Claude/GPT weekly", "weekly"),
            row("c5", "antigravity", "Claude/GPT 5-hour", "session"),
        )
        val sections = quotaSections(quotaAccounts(readings, emptyList()).single())
        assertEquals(listOf("Gemini", "Claude/GPT", "Gemini 4 Argon"), sections.map { it.title })
        assertEquals(listOf(listOf("g5", "gw"), listOf("c5", "cw"), listOf("aw")),
            sections.flatMap { it.rows }.map { row -> row.map { it.reading.id } })
    }

    @Test fun openCodeMonthAndZenAreSeparateFromThePairedWindows() {
        val readings = listOf(
            row("week", "opencode", "weekly", "weekly"),
            row("month", "opencode", "billing", "billing").copy(windowMinutes = 43200),
            row("short", "opencode", "session", "session").copy(windowMinutes = 300),
            row("wallet", "opencode", "Balance", "billing").copy(kind = "balance", amount = 0.0, currency = "USD"),
        )
        val account = quotaAccounts(readings, emptyList()).single()
        val sections = quotaSections(requireNotNull(account.forKind("quota")))
        assertEquals(listOf("Go 额度", null), sections.map { it.title })
        assertEquals(listOf(listOf("5 小时", "每周"), listOf("每月"), listOf("Zen 余额")),
            sections.flatMap { it.rows }.map { row -> row.map { it.title } })
        val wallet = requireNotNull(account.forKind("balance")).readings.single()
        assertEquals(0.0, requireNotNull(wallet.amount), 0.0)
        assertEquals("USD", wallet.currency)
    }

    @Test fun accountsWithoutWindowsAndNamedBillingWindowsRemainVisible() {
        val readings = listOf(
            row("c", "codex", "weekly", "weekly"),
            row("p", "copilot", "Premium", "billing"),
            row("h", "copilot", "Chat", "billing").copy(remainingPercent = null),
            row("a", "codex", "session", "session").copy(accountId = "work"),
            row("wallet", "openrouter", "Balance", "billing").copy(kind = "balance"),
        )
        val cursor = ToolSource("cursor", "Cursor", "cursor-account", "unavailable")
        val accounts = quotaAccounts(readings, listOf(cursor)).mapNotNull { it.forKind("quota") }
        assertEquals(listOf("codex", "copilot", "codex", "cursor"), accounts.map { it.providerId })
        assertEquals(listOf(listOf("每周")), quotaSections(accounts.first()).single().rows.map { row -> row.map { it.title } })
        val copilot = quotaSections(accounts[1]).single().rows
        assertEquals(listOf(listOf("Premium"), listOf("Chat")), copilot.map { row -> row.map { it.title } })
        assertNull(copilot.last().single().reading.remainingPercent)
        assertEquals("unavailable", accounts.last().source?.status)
        assertTrue(accounts.last().readings.isEmpty())
    }

    private fun row(id: String, provider: String, label: String, kind: String) =
        Reading(id, provider, label = label, kind = "quota", remainingPercent = 25.0,
            providerId = provider, accountId = "personal", windowKind = kind)
}

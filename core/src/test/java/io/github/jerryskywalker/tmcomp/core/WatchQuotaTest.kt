package io.github.jerryskywalker.tmcomp.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchQuotaTest {
    @Test
    fun groupsByProviderAndAccountWhileKeepingDifferentWindows() {
        val fresh = quota("new", "one", "5 小时", 300)
        val old = fresh.copy(id = "old", stale = true)
        val daily = quota("daily", "one", "每日", 1440)
        val weekly = quota("weekly", "one", "每周", 10080)
        val monthly = quota("monthly", "one", "每月", 43200)
        val separateAccount = fresh.copy(id = "account-two", accountId = "two")
        val rows = listOf(old, fresh, daily, weekly, monthly, separateAccount)
        val short = watchQuotaGroups(rows, WatchQuotaCategory.SHORT).single()
        assertEquals("Codex", short.provider)
        assertEquals(listOf(listOf("new", "daily"), listOf("account-two")),
            short.accounts.map { account -> account.windows.map { it.id } })
        val cycle = watchQuotaGroups(rows, WatchQuotaCategory.CYCLE).single()
        assertEquals(listOf("weekly", "monthly"), cycle.accounts.single().windows.map { it.id })
    }

    private fun quota(id: String, account: String, label: String, minutes: Long) =
        Reading(id, "Codex", label = label, kind = "quota", providerId = "codex", accountId = account, windowMinutes = minutes)
}

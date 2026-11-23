package io.github.jerryskywalker.tmcomp.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class UsageDetailsTest {
    @Test
    fun rollingHoursUseObservedToolDeltasAndSkipMidnightAndMissingIntervals() {
        val now = Instant.parse("2026-11-23T00:40:00Z")
        val samples = listOf(
            sample("2026-11-22T23:35:00Z", 6, 6),
            sample("2026-11-22T23:44:00Z", 10, 10),
            sample("2026-11-22T23:56:00Z", 14, 14),
            sample("2026-11-23T00:00:00Z", 20, 20),
            sample("2026-11-23T00:10:00Z", 35, 27),
            sample("2026-11-23T00:35:00Z", 80, 61),
            sample("2026-11-23T00:44:00Z", 90, 65),
        )
        val snapshot = Snapshot(1, retrievedAt = now.toString(), stale = false, today = Period(), month = Period(),
            readings = emptyList(), trendSamples = samples)
        val period = requireNotNull(snapshot.hourlyUsage(Range.LAST_HOUR, null, now, ZoneOffset.UTC))
        assertEquals(19L, period.tokens)
        assertEquals(listOf("codex" to 11L, "cursor" to 8L), period.tools.map { it.key to it.tokens })
        val trend = snapshot.trend(Range.LAST_HOUR, zone = ZoneOffset.UTC, now = now)
        assertEquals("23:40", trend.start)
        assertEquals("00:40", trend.end)
        assertEquals(listOf(4L, null, 15L, null), trend.points.map { it.tokens })
        assertEquals(TrendInterval.MIN_15, TrendIntervals(intraday = TrendInterval.MIN_15).forRange(Range.LAST_2_HOURS))
    }

    @Test
    fun quotaDifferencesSkipResetsRefillsAndAllowanceChanges() {
        val at = Instant.parse("2026-11-23T04:00:00Z")
        val first = requireNotNull(Reading("one", "Tool", label = "Weekly", kind = "quota", remainingPercent = 80.0,
            limit = 250.0, observedAt = at.toString(), boundaryAt = at.plusSeconds(3_600).toString()).observeQuota(at, ZoneOffset.UTC))
        val later = first.copy(at = first.at + 1000, value = 76.0)
        assertEquals(4.0, later.changeSince(first)!!, 0.0)
        assertNull(later.copy(allowance = 500.0).changeSince(first))
        assertNull(later.copy(resetAt = first.resetAt!! + 86_400_000).changeSince(first))
        assertNull(later.copy(value = 100.0).changeSince(first))
        assertEquals(0.0, later.copy(value = 80.0).changeSince(first)!!, 0.0)
        assertNull(first.changeSince(first))
    }

    @Test
    fun billingDatesRetainMonthEndAnchorAndRespectCancellation() {
        val plan = HubSubscription("test", "codex", startDate = "2026-01-31", interval = "month")
        assertEquals(LocalDate.parse("2026-02-28"), plan.nextRenewal(LocalDate.parse("2026-02-20")))
        assertEquals(LocalDate.parse("2026-03-31"), plan.nextRenewal(LocalDate.parse("2026-03-01")))
        assertNull(plan.copy(autoRenew = false).nextRenewal(LocalDate.parse("2026-03-01")))
        assertNull(plan.copy(endDate = "2026-03-31").nextRenewal(LocalDate.parse("2026-03-01")))
        assertEquals(LocalDate.parse("2026-03-14"), plan.copy(nextRenewalOverride = "2026-03-14").nextRenewal(LocalDate.parse("2026-03-01")))
    }

    private fun sample(at: String, total: Long, codex: Long) = TrendSample(Instant.parse(at).toEpochMilli(),
        TrendCounter(total, tools = listOf(UsageEntry("codex", "Codex", codex), UsageEntry("cursor", "Cursor", total - codex))))
}

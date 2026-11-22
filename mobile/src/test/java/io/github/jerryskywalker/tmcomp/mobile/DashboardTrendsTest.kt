package io.github.jerryskywalker.tmcomp.mobile

import io.github.jerryskywalker.tmcomp.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class DashboardTrendsTest {
    private val day = LocalDate.parse("2026-03-01")
    private val now = "2026-03-01T14:00:00Z"

    @Test
    fun rangesFollowCalendarBoundariesAndKeepMissingDays() {
        val data = snapshot(listOf(TrendDay("2026-02-27", 8), TrendDay("2026-03-01", 0)))
        assertEquals("2026-02-23", data.daysFor(Range.WEEK, day).first().date)
        assertEquals("2026-03-01", data.daysFor(Range.MONTH, day).first().date)
        assertEquals("2026-01-01", data.daysFor(Range.YEAR, day).first().date)
        assertEquals(365, data.daysFor(Range.LAST_365, day).size)
        assertEquals(30, data.daysFor(Range.LAST_30, day).size)
        assertEquals(listOf(8L, null, 0L), data.daysFor(Range.LAST_7, day).takeLast(3).map { it.tokens })
        assertEquals(List(7) { null }, data.trend(DashboardScope(Range.LAST_7, "device"), day).points.map { it.tokens })
    }

    @Test
    fun samplesKeepRealDeltaAndLeaveOutagesAndCounterResetsUnknown() {
        val samples = listOf(
            sample("2026-02-28T23:59:00Z", 20, 8),
            sample("2026-03-01T00:04:00Z", 100, 10),
            sample("2026-03-01T00:09:00Z", 125, 12),
            sample("2026-03-01T00:35:00Z", 270, 19),
            sample("2026-03-01T00:40:00Z", 2, null),
            sample("2026-03-01T00:45:00Z", 2, null),
        )
        val data = snapshot(samples = samples)
        val series = data.trend(DashboardScope(), day, ZoneOffset.UTC)
        assertEquals(listOf(25L, null, null, 0L), series.points.map { it.tokens })
        assertEquals("00:04–00:09", series.points.first().label)
        assertEquals(listOf(2L, null, null, null),
            data.trend(DashboardScope(device = "device"), day, ZoneOffset.UTC).points.map { it.tokens })
    }

    @Test
    fun candleUsesOnlyObservedOpenHighLowAndClose() {
        val series = TrendSeries(listOf(6L, null, 13L, 2L, 7L).mapIndexed { i, value ->
            TrendPoint("$i", i / 100f, value, null)
        }, "start", "end", true)
        val candle = series.candles(false).first()
        assertEquals(6.0, candle.open, 0.0)
        assertEquals(13.0, candle.high, 0.0)
        assertEquals(2.0, candle.low, 0.0)
        assertEquals(7.0, candle.close, 0.0)
        assertTrue(series.candles(true).isEmpty())
    }

    @Test
    fun migratesPreviouslyOrderedCardsAndKeepsHiddenCardsOut() {
        val cards = migrateOverviewCards(listOf(CardOption("TOOLS"), CardOption("TREND", visible = false), CardOption("OLD")))
        assertEquals(OverviewCard.TOOLS, cards.first())
        assertFalse(OverviewCard.TREND in cards)
        assertTrue(OverviewCard.SUMMARY in cards)
    }

    private fun sample(at: String, total: Long, device: Long?) =
        TrendSample(Instant.parse(at).toEpochMilli(), TrendCounter(total), device?.let { mapOf("device" to TrendCounter(it)) }.orEmpty())

    private fun snapshot(history: List<TrendDay> = emptyList(), samples: List<TrendSample> = emptyList()) =
        Snapshot(1, retrievedAt = now, stale = false, today = Period(), month = Period(),
            readings = emptyList(), history = history, trendSamples = samples)
}

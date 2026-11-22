package io.github.jerryskywalker.tmcomp.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlinx.serialization.Serializable

enum class Range(val label: String) {
    TODAY("当日"), LAST_7("7 天"), LAST_30("30 天"), LAST_365("365 天"),
    WEEK("本周"), MONTH("本月"), YEAR("本年"), TOTAL("累计"),
}


enum class TrendStyle(val label: String) { LINE("折线"), CANDLE("K 线") }

enum class TrendInterval(val label: String, val minutes: Int = 0, val days: Int = 0) {
    NATIVE("原始采样"), MIN_15("15 分钟", minutes = 15), MIN_30("30 分钟", minutes = 30),
    HOUR("1 小时", minutes = 60), AUTO("自动"), DAY("每日", days = 1), DAYS_7("每 7 天", days = 7),
    DAYS_30("每 30 天", days = 30);

    companion object {
        fun forRange(range: Range) = if (range == Range.TODAY) listOf(NATIVE, MIN_15, MIN_30, HOUR)
            else listOf(AUTO, DAY, DAYS_7, DAYS_30)
    }
}

@Serializable
data class TrendIntervals(val intraday: TrendInterval = TrendInterval.NATIVE, val daily: TrendInterval = TrendInterval.AUTO) {
    fun forRange(range: Range): TrendInterval {
        val saved = if (range == Range.TODAY) intraday else daily
        val supported = TrendInterval.forRange(range)
        return saved.takeIf { it in supported } ?: supported.first()
    }

    fun withRange(range: Range, interval: TrendInterval) =
        if (range == Range.TODAY) copy(intraday = interval) else copy(daily = interval)
}

@Serializable
data class WatchTrendSettings(
    val range: Range = Range.LAST_7,
    val intervals: TrendIntervals = TrendIntervals(),
    val style: TrendStyle = TrendStyle.LINE,
    val cost: Boolean = false,
)

data class TrendPoint(val label: String, val position: Float, val tokens: Long?, val cost: Double?) {
    fun value(costMetric: Boolean): Double? = if (costMetric) cost else tokens?.toDouble()
}

data class TrendSeries(
    val points: List<TrendPoint>, val start: String, val end: String, val intraday: Boolean = false,
    val candleBuckets: List<List<TrendPoint>>? = null,
)

data class Candle(val position: Float, val open: Double, val high: Double, val low: Double, val close: Double,
    val label: String)

fun Range.startDay(today: LocalDate): LocalDate = when (this) {
    Range.TODAY -> today
    Range.LAST_7 -> today.minusDays(6)
    Range.LAST_30 -> today.minusDays(29)
    Range.LAST_365 -> today.minusDays(364)
    Range.WEEK -> today.minusDays(today.dayOfWeek.value.toLong() - 1)
    Range.MONTH -> today.withDayOfMonth(1)
    Range.YEAR -> today.withDayOfYear(1)
    Range.TOTAL -> today.minusDays(365)
}

fun Snapshot.daysFor(range: Range, today: LocalDate = LocalDate.now()): List<TrendDay> {
    val start = range.startDay(today)
    val count = ChronoUnit.DAYS.between(start, today).toInt() + 1
    val rows = history.associateBy { it.date }
    return List(count) { offset ->
        val key = start.plusDays(offset.toLong()).toString()
        rows[key] ?: TrendDay(key)
    }
}

fun Snapshot.trend(
    range: Range, device: String? = null, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
    maxGapMinutes: Long = 20, interval: TrendInterval = TrendInterval.NATIVE,
): TrendSeries {
    if (range != Range.TODAY) {
        val days = daysFor(range, today)
        val points = days.mapIndexed { index, row ->
            TrendPoint(row.date, index.toFloat() / (days.lastIndex.coerceAtLeast(1)),
                row.tokens.takeIf { device == null }, row.costUsd.takeIf { device == null })
        }
        val buckets = if (interval.days > 0) points.chunked(interval.days) else null
        return TrendSeries(buckets?.map(::sumBucket) ?: points, days.first().date, days.last().date,
            candleBuckets = buckets)
    }
    val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val samples = trendSamples.filter { it.at in start until end }.sortedBy { it.at }
    fun TrendSample.counter() = if (device == null) total else devices[device]
    val points = samples.zipWithNext { before, after ->
        val a = before.counter()
        val b = after.counter()
        val connected = after.at - before.at in 1..(maxGapMinutes * 60_000L)
        val oldTokens = a?.tokens
        val newTokens = b?.tokens
        val oldCost = a?.costUsd
        val newCost = b?.costUsd
        val tokenDelta = if (connected && oldTokens != null && newTokens != null && newTokens >= oldTokens)
            newTokens - oldTokens else null
        val costDelta = if (connected && oldCost != null && newCost != null && newCost >= oldCost)
            newCost - oldCost else null
        fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalTime().withSecond(0).withNano(0)
        TrendPoint("${clock(before.at)}–${clock(after.at)}", (after.at - start).toFloat() / (end - start), tokenDelta, costDelta)
    }
    val buckets = if (interval.minutes > 0) points.groupBy {
        (it.position * ((end - start) / (interval.minutes * 60_000.0))).toInt()
    }.values.toList() else null
    return TrendSeries(buckets?.map(::sumBucket) ?: points, "00:00", "24:00", intraday = true, candleBuckets = buckets)
}

private fun sumBucket(points: List<TrendPoint>): TrendPoint {
    val first = points.first()
    val last = points.last()
    return TrendPoint(
        if (first == last) first.label else "${first.label.substringBefore('–')}–${last.label.substringAfterLast('–')}",
        last.position,
        points.mapNotNull { it.tokens }.takeIf { it.size == points.size }?.sum(),
        points.mapNotNull { it.cost }.takeIf { it.size == points.size }?.sum(),
    )
}

fun TrendSeries.candles(cost: Boolean): List<Candle> {
    // Keep the empty buckets on the time axis. A bucket summarizes its known observations.
    val buckets = candleBuckets ?: if (intraday) points.groupBy { (it.position * 24).toInt() }.values
    else points.reversed().chunked(maxOf(2, ceil(points.size / 24f).toInt()))
        .asReversed().map { it.asReversed() }
    return buckets.mapNotNull { bucket ->
        val values = bucket.mapNotNull { it.value(cost) }
        if (values.isEmpty()) null else Candle(
            (bucket.first().position + bucket.last().position) / 2,
            values.first(), values.max(), values.min(), values.last(),
            if (intraday) "${bucket.first().label.take(5)}–${bucket.last().label.takeLast(5)}"
                else "${bucket.first().label}–${bucket.last().label}",
        )
    }
}

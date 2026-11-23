package io.github.jerryskywalker.tmcomp.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlinx.serialization.Serializable

enum class Range(val label: String, val rollingHours: Long = 0) {
    TODAY("当日"), LAST_7("7 天"), LAST_30("30 天"), LAST_365("365 天"),
    WEEK("本周"), MONTH("本月"), YEAR("本年"), TOTAL("累计"),
    LAST_12_HOURS("过去 12 小时", 12), LAST_8_HOURS("过去 8 小时", 8), LAST_4_HOURS("过去 4 小时", 4),
    LAST_2_HOURS("过去 2 小时", 2), LAST_HOUR("过去 1 小时", 1);

    val intraday: Boolean get() = this == TODAY || rollingHours > 0
}


enum class TrendStyle(val label: String) { LINE("折线"), CANDLE("K 线") }

enum class CandleColors(val label: String) {
    GREEN_UP("绿涨红跌"), RED_UP("红涨绿跌");

    fun usesGreen(rising: Boolean): Boolean = rising == (this == GREEN_UP)
}

enum class TrendInterval(val label: String, val minutes: Int = 0, val days: Int = 0) {
    NATIVE("原始采样"), MIN_15("15 分钟", minutes = 15), MIN_30("30 分钟", minutes = 30),
    HOUR("1 小时", minutes = 60), AUTO("自动"), DAY("每日", days = 1), DAYS_7("每 7 天", days = 7),
    DAYS_30("每 30 天", days = 30);

    companion object {
        fun forRange(range: Range) = if (range.intraday) listOf(NATIVE, MIN_15, MIN_30, HOUR)
            else listOf(AUTO, DAY, DAYS_7, DAYS_30)
    }
}

@Serializable
data class TrendIntervals(val intraday: TrendInterval = TrendInterval.NATIVE, val daily: TrendInterval = TrendInterval.AUTO) {
    fun forRange(range: Range): TrendInterval {
        val saved = if (range.intraday) intraday else daily
        val supported = TrendInterval.forRange(range)
        return saved.takeIf { it in supported } ?: supported.first()
    }

    fun withRange(range: Range, interval: TrendInterval) =
        if (range.intraday) copy(intraday = interval) else copy(daily = interval)
}

@Serializable
data class WatchTrendSettings(
    val range: Range = Range.LAST_7,
    val intervals: TrendIntervals = TrendIntervals(),
    val style: TrendStyle = TrendStyle.LINE,
    val cost: Boolean = false,
    val candleColors: CandleColors = CandleColors.GREEN_UP,
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
    else -> today
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
    maxGapMinutes: Long = 20, interval: TrendInterval = TrendInterval.NATIVE, now: Instant = Instant.now(),
): TrendSeries {
    if (!range.intraday) {
        val days = daysFor(range, today)
        val points = days.mapIndexed { index, row ->
            TrendPoint(row.date, index.toFloat() / (days.lastIndex.coerceAtLeast(1)),
                row.tokens.takeIf { device == null }, row.costUsd.takeIf { device == null })
        }
        val buckets = if (interval.days > 0) points.chunked(interval.days) else null
        return TrendSeries(buckets?.map(::sumBucket) ?: points, days.first().date, days.last().date,
            candleBuckets = buckets)
    }
    val rolling = range.rollingHours > 0
    val end = if (rolling) now.toEpochMilli() else today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val start = if (rolling) end - range.rollingHours * 3_600_000L else today.atStartOfDay(zone).toInstant().toEpochMilli()
    val samples = trendSamples.filter { it.at in start..end && (rolling || it.at != end) }.sortedBy { it.at }
    fun TrendSample.counter() = if (device == null) total else devices[device]
    fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()
    val points = samples.zipWithNext { before, after ->
        val a = before.counter()
        val b = after.counter()
        val connected = sameCounterDay(before.at, after.at, zone) && after.at - before.at in 1..(maxGapMinutes * 60_000L)
        val oldTokens = a?.tokens
        val newTokens = b?.tokens
        val oldCost = a?.costUsd
        val newCost = b?.costUsd
        val tokenDelta = if (connected && oldTokens != null && newTokens != null && newTokens >= oldTokens)
            newTokens - oldTokens else null
        val costDelta = if (connected && oldCost != null && newCost != null && newCost >= oldCost)
            newCost - oldCost else null
        TrendPoint("${clock(before.at)}–${clock(after.at)}", (after.at - start).toFloat() / (end - start), tokenDelta, costDelta)
    }
    fun bucketsOf(minutes: Int) = points.groupBy {
        (it.position * ((end - start) / (minutes * 60_000.0))).toInt()
    }.values.toList()
    val buckets = interval.minutes.takeIf { it > 0 }?.let(::bucketsOf)
    val candleBuckets = buckets ?: if (rolling) bucketsOf(if (range.rollingHours <= 4) 15 else 30) else null
    return TrendSeries(buckets?.map(::sumBucket) ?: points, if (rolling) clock(start) else "00:00",
        if (rolling) clock(end) else "24:00", intraday = true, candleBuckets = candleBuckets)
}

internal fun sameCounterDay(before: Long, after: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(before).atZone(zone).toLocalDate() == Instant.ofEpochMilli(after).atZone(zone).toLocalDate()

/** Observed increments inside a rolling range. Samples across midnight or a gap are unallocated. */
fun Snapshot.hourlyUsage(range: Range, device: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Period? {
    if (range.rollingHours == 0L) return null
    val end = now.toEpochMilli()
    val samples = trendSamples.filter { it.at in (end - range.rollingHours * 3_600_000L)..end }.sortedBy { it.at }
    fun TrendSample.counter() = if (device == null) total else devices[device]
    val pairs = samples.zipWithNext().filter { (a, b) -> b.at - a.at in 1..1_200_000L && sameCounterDay(a.at, b.at, zone) }
    val tokens = mutableListOf<Long>()
    val costs = mutableListOf<Double>()
    val tools = linkedMapOf<String, UsageEntry>()
    for ((a, b) in pairs) {
        val before = a.counter() ?: continue
        val after = b.counter() ?: continue
        val oldTokens = before.tokens
        val newTokens = after.tokens
        if (oldTokens == null || newTokens == null || newTokens < oldTokens) continue
        tokens += newTokens - oldTokens
        val oldCost = before.costUsd
        val newCost = after.costUsd
        if (oldCost != null && newCost != null && newCost >= oldCost) costs += newCost - oldCost
        val oldTools = before.tools.associateBy { it.key }
        val oldComplete = before.tools.sumOf { it.tokens } == oldTokens
        for (entry in after.tools) {
            val baseline = oldTools[entry.key]?.tokens ?: if (oldComplete) 0 else continue
            if (entry.tokens < baseline) continue
            val delta = entry.tokens - baseline
            tools[entry.key] = entry.copy(tokens = (tools[entry.key]?.tokens ?: 0) + delta, costUsd = null)
        }
    }
    return Period(tokens = tokens.takeIf { it.isNotEmpty() }?.sum(), costUsd = costs.takeIf { it.isNotEmpty() }?.sum(),
        tools = tools.values.sortedByDescending { it.tokens })
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

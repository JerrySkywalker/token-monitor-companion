package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

enum class Range(val label: String) {
    TODAY("当日"), LAST_7("7 天"), LAST_30("30 天"), LAST_365("365 天"),
    WEEK("本周"), MONTH("本月"), YEAR("本年"), TOTAL("累计"),
}

internal val rangeChoices = Range.entries.filter { it != Range.TOTAL }
internal val defaultRanges = listOf(Range.TODAY, Range.LAST_7, Range.LAST_30, Range.MONTH)
internal const val OVERVIEW_RANGES_KEY = "overview_ranges_v1"
internal const val TREND_STYLE_KEY = "trend_style_v1"

internal enum class TrendStyle(val label: String) { LINE("折线"), CANDLE("K 线") }

internal data class TrendPoint(val label: String, val position: Float, val tokens: Long?, val cost: Double?) {
    fun value(costMetric: Boolean): Double? = if (costMetric) cost else tokens?.toDouble()
}

internal data class TrendSeries(
    val points: List<TrendPoint>, val start: String, val end: String, val intraday: Boolean = false,
)

internal data class Candle(val position: Float, val open: Double, val high: Double, val low: Double, val close: Double,
    val label: String)

internal fun Range.startDay(today: LocalDate): LocalDate = when (this) {
    Range.TODAY -> today
    Range.LAST_7 -> today.minusDays(6)
    Range.LAST_30 -> today.minusDays(29)
    Range.LAST_365 -> today.minusDays(364)
    Range.WEEK -> today.minusDays(today.dayOfWeek.value.toLong() - 1)
    Range.MONTH -> today.withDayOfMonth(1)
    Range.YEAR -> today.withDayOfYear(1)
    Range.TOTAL -> today.minusDays(365)
}

internal fun Snapshot.daysFor(range: Range, today: LocalDate = LocalDate.now()): List<TrendDay> {
    val start = range.startDay(today)
    val count = ChronoUnit.DAYS.between(start, today).toInt() + 1
    val rows = history.associateBy { it.date }
    return List(count) { offset ->
        val key = start.plusDays(offset.toLong()).toString()
        rows[key] ?: TrendDay(key)
    }
}

internal fun Snapshot.trend(
    scope: DashboardScope, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
): TrendSeries {
    if (scope.range != Range.TODAY) {
        val days = daysFor(scope.range, today)
        return TrendSeries(days.mapIndexed { index, row ->
            TrendPoint(row.date, index.toFloat() / (days.lastIndex.coerceAtLeast(1)),
                row.tokens.takeIf { scope.device == null }, row.costUsd.takeIf { scope.device == null })
        }, days.first().date, days.last().date)
    }
    val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val samples = trendSamples.filter { it.at in start until end }.sortedBy { it.at }
    fun TrendSample.counter() = if (scope.device == null) total else devices[scope.device]
    val points = samples.zipWithNext { before, after ->
        val a = before.counter()
        val b = after.counter()
        val connected = after.at - before.at in 1..(20 * 60_000L)
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
    return TrendSeries(points, "00:00", "24:00", intraday = true)
}

internal fun TrendSeries.candles(cost: Boolean): List<Candle> {
    // Keep the empty buckets on the time axis. A bucket summarizes its known observations.
    val buckets = if (intraday) points.groupBy { (it.position * 24).toInt() }.values
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

@Composable
internal fun TrendPlot(series: TrendSeries, style: TrendStyle = TrendStyle.LINE, cost: Boolean = false, compact: Boolean = false) {
    val values = series.points.map { it.value(cost) }
    val known = values.filterNotNull()
    if (known.isEmpty()) {
        Text(if (series.intraday) "当日趋势等待连续采样" else "此范围暂无趋势数据",
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val peak = known.max().coerceAtLeast(1.0)
    val good = MaterialTheme.colorScheme.primary
    val down = Color(0xffad5348)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val candles = if (style == TrendStyle.CANDLE) series.candles(cost) else emptyList()
    val unit = if (cost) "USD" else "Tokens"
    var selected by remember(series, style, cost) { mutableStateOf<Float?>(null) }
    val point = selected?.let { position -> series.points.minByOrNull { kotlin.math.abs(it.position - position) } }
    val candle = selected?.let { position -> candles.minByOrNull { kotlin.math.abs(it.position - position) } }
    Text(if (series.intraday) "采样间增量 · $unit" else "按日记录 · $unit", style = MaterialTheme.typography.labelMedium)
    val interact = if (compact) Modifier else Modifier.pointerInput(series, style, cost) {
        detectTapGestures { touch ->
            val inset = 5.dp.toPx()
            selected = ((touch.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(if (compact) 86.dp else 168.dp).then(interact).semantics {
        contentDescription = "${style.label} · $unit · ${series.start} 至 ${series.end} · ${known.size} 个有效读数"
    }) {
        val inset = 5.dp.toPx()
        val width = size.width - 2 * inset
        val height = size.height - 2 * inset
        fun x(fraction: Float) = inset + width * fraction
        fun y(value: Double) = inset + height * (1 - (value / peak).toFloat())
        repeat(4) { i ->
            val step = inset + height * i / 3
            drawLine(grid, Offset(inset, step), Offset(size.width - inset, step))
        }
        if (style == TrendStyle.LINE) {
            var last: Offset? = null
            series.points.zip(values).forEach { (point, value) ->
                if (value == null) last = null else {
                    val next = Offset(x(point.position), y(value))
                    last?.let { drawLine(good, it, next, 2.dp.toPx()) }
                    if (values.size < 80 || last == null) drawCircle(good, 2.dp.toPx(), next)
                    last = next
                }
            }
        } else {
            candles.forEach { candle ->
                val center = x(candle.position)
                val color = if (candle.close >= candle.open) good else down
                val top = y(maxOf(candle.open, candle.close))
                val bottom = y(minOf(candle.open, candle.close))
                val half = minOf(5.dp.toPx(), width * .022f)
                drawLine(color, Offset(center, y(candle.high)), Offset(center, y(candle.low)), 1.dp.toPx())
                drawRect(color, Offset(center - half, top), Size(half * 2, (bottom - top).coerceAtLeast(1.dp.toPx())))
            }
        }
        (if (style == TrendStyle.CANDLE) candle?.position else point?.position)?.let { position ->
            drawLine(good.copy(alpha = .40f), Offset(x(position), inset), Offset(x(position), size.height - inset))
        }
    }
    if (series.intraday && !compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    } else LedgerLine(series.start, series.end)
    if (style == TrendStyle.LINE && point != null) LedgerLine(point.label, displayNumber(point.value(cost)) + " " + unit)
    if (!compact && style == TrendStyle.CANDLE) {
        if (candle == null) {
            Text("每根 K 线汇总该时间段首、尾、最高和最低观测值。", style = MaterialTheme.typography.labelMedium)
        } else {
            Text(candle.label, style = MaterialTheme.typography.labelMedium)
            Text("开 ${displayNumber(candle.open)} · 高 ${displayNumber(candle.high)} · 低 ${displayNumber(candle.low)} · 收 ${displayNumber(candle.close)}",
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

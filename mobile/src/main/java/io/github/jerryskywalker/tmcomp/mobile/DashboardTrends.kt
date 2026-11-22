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
import java.time.LocalDate
import java.time.ZoneId

internal val rangeChoices = Range.entries.filter { it != Range.TOTAL }
internal val defaultRanges = listOf(Range.TODAY, Range.LAST_7, Range.LAST_30, Range.MONTH)
internal const val OVERVIEW_RANGES_KEY = "overview_ranges_v1"
internal const val TREND_STYLE_KEY = "trend_style_v1"

internal const val TREND_INTERVAL_KEY = "trend_interval_v1"

internal fun Snapshot.trend(
    scope: DashboardScope, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
    interval: TrendInterval = TrendInterval.NATIVE,
) = trend(scope.range, scope.device, today, zone, interval = interval)

@Composable
internal fun TrendPlot(series: TrendSeries, style: TrendStyle = TrendStyle.LINE, cost: Boolean = false, compact: Boolean = false) {
    val values = series.points.map { it.value(cost) }
    val known = values.filterNotNull()
    val candles = if (style == TrendStyle.CANDLE) series.candles(cost) else emptyList()
    val maximum = if (style == TrendStyle.CANDLE) candles.maxOfOrNull { it.high } else known.maxOrNull()
    if (maximum == null) {
        Text(if (series.intraday) "当日趋势等待连续采样" else "此范围暂无趋势数据",
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val peak = maximum.coerceAtLeast(1.0)
    val good = MaterialTheme.colorScheme.primary
    val down = Color(0xffad5348)
    val grid = MaterialTheme.colorScheme.outlineVariant
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
        val count = if (style == TrendStyle.CANDLE) candles.size else known.size
        contentDescription = "${style.label} · $unit · ${series.start} 至 ${series.end} · $count 个有效${if (style == TrendStyle.CANDLE) "分桶" else "读数"}"
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
    } else if (compact && !series.intraday) LedgerLine(series.start.takeLast(5), series.end.takeLast(5))
    else LedgerLine(series.start, series.end)
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

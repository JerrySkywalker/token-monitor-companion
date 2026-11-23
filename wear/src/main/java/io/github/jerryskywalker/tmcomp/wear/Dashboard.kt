package io.github.jerryskywalker.tmcomp.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import io.github.jerryskywalker.tmcomp.core.*
import java.time.Instant
import kotlinx.coroutines.launch

private val track = Color(0xff293445)
private val muted = Color(0xffabc0da)
private val panel = Color(0xff131a25)
private val bright = Color(0xfff2f6fd)
private val warning = Color(0xffffa097)
private val palette = mapOf(
    "codex" to Color(0xff58d9ea), "cursor" to Color(0xffc49bff), "opencode" to Color(0xffff82b7),
    "copilot" to Color(0xfff4d16e), "github-copilot" to Color(0xfff4d16e), "antigravity" to Color(0xff91d789),
)
private fun hue(key: String?) = palette[key?.lowercase()] ?: muted
private fun fresh(row: Reading, budget: Long, now: Instant): Boolean {
    val boundary = row.boundaryAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return !row.stale && !locallyStale(row.observedAt, budget) && (boundary == null || now.isBefore(boundary))
}
private fun countdown(at: String?, now: Instant): String {
    val reset = at?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "重置未知"
    val minutes = ((reset.epochSecond - now.epochSecond) / 60).coerceAtLeast(0)
    return when {
        minutes < 60 -> "$minutes 分后"
        minutes < 1440 -> (minutes / 60).toString() + " 小时 " + minutes % 60 + " 分"
        else -> (minutes / 1440).toString() + " 天 " + minutes % 1440 / 60 + " 小时"
    }
}

@Composable
internal fun StopWatchDashboard(
    data: Snapshot, config: WatchConfig?, status: String, ringChoice: String?, opensSettings: () -> Unit, refresh: () -> Unit,
) {
    val names = listOf("总览", "额度", "趋势", "设备")
    val pager = rememberPagerState { names.size }
    val action = rememberCoroutineScope()
    var quota by rememberSaveable { mutableIntStateOf(0) }
    var cycle by rememberSaveable { mutableStateOf(false) }
    val display = config?.display ?: WatchDisplay()
    val now = Instant.now()
    val cached = data.stale || locallyStale(data.upstreamAt, data.freshnessMs) ||
        config?.paused == true || status.contains("失败") || status.contains("不可达")
    val ringKey = ringChoice ?: data.readings.firstOrNull { it.id == config?.pinnedId }?.toolKey
    val provider = data.readings.firstOrNull { it.toolKey == ringKey }?.provider ?: ringKey
    val ringCategory = if (cycle) WatchQuotaCategory.CYCLE else WatchQuotaCategory.SHORT
    val ring = data.readings.filter { it.toolKey == ringKey && it.watchQuotaCategory == ringCategory &&
        !cached && fresh(it, data.freshnessMs, now) }.mapNotNull { it.remainingPercent }.minOrNull()
    val cap = if (!ringKey.isNullOrEmpty()) (if (cycle) "周期 " else "短时 ") + provider + " " +
        (ring?.let { displayNumber(it) + "%" } ?: "—") else "Token Monitor"

    BoxWithConstraints(Modifier.fillMaxSize().background(if (display.oled) Color.Black else Color(0xff05070a))) {
        val w = maxWidth
        val h = maxHeight
        if (!ringKey.isNullOrEmpty()) Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val d = minOf(size.width, size.height)
            val area = Size(d, d)
            val offset = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 120f, 300f, false, offset, area, style = Stroke(2.dp.toPx()))
            if (ring != null) drawArc(hue(ringKey), 120f, (ring * 3).toFloat(), false, offset, area, style = Stroke(2.dp.toPx()))
        }
        TimeText(modifier = Modifier.align(Alignment.TopCenter))
        Text(cap, Modifier.align(Alignment.TopCenter).offset(y = h * .11f).widthIn(max = w * .72f),
            fontSize = 10.sp, color = if (ring == null) muted else hue(ringKey), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.align(Alignment.TopCenter).offset(y = h * .16f).height(27.dp).width(w * .72f),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(names[pager.currentPage], fontSize = 17.sp, fontWeight = FontWeight.Bold, color = bright)
            Text(" ⚙", Modifier.clickable(onClick = opensSettings).padding(horizontal = 6.dp)
                .semantics { contentDescription = "手表连接与外圈设置" }, fontSize = 15.sp, color = muted)
        }
        HorizontalPager(pager, Modifier.align(Alignment.TopCenter).offset(y = h * .267f).height(h * .532f).fillMaxWidth()) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.width(w * .70f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    when (page) {
                        0 -> WatchOverview(data)
                        1 -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                                WatchQuotaCategory.entries.forEachIndexed { i, category ->
                                    Text(category.label, Modifier.clickable { quota = i; if (i != 2) cycle = i == 1 }
                                        .padding(3.dp), fontSize = 11.sp, color = if (quota == i) bright else muted)
                                }
                            }
                            val category = WatchQuotaCategory.entries[quota]
                            val groups = watchQuotaGroups(data.readings, category, display.quotaOrders[category].orEmpty())
                            if (groups.isEmpty()) Text("暂无读数", fontSize = 11.sp, color = muted)
                            groups.forEach { group -> WatchProviderQuota(group, data.freshnessMs, cached, now, display.oled) }
                        }
                        2 -> WatchTrend(data, display.trend, if (config?.mode == Mode.DIRECT) config.refreshMinutes * 2 else 20)
                        3 -> {
                            if (data.collectors.isEmpty()) Text("暂无设备数据", fontSize = 11.sp, color = muted)
                            data.collectors.forEach { device ->
                                Column(Modifier.fillMaxWidth().padding(bottom = 3.dp)
                                    .background(panel, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                                    WatchRow(device.label, displayCompactTokens(device.today.tokens))
                                    val stale = cached || device.stale || locallyStale(device.updatedAt, data.freshnessMs)
                                    WatchRow((if (stale) "缓存 " else "上报 ") + displayClock(device.updatedAt),
                                        "月 " + displayCompactTokens(device.month.tokens), if (stale) warning else muted)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).offset(y = h * .80f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            names.forEachIndexed { i, name ->
                Text(if (pager.currentPage == i) "●" else "·", Modifier.clickable {
                    action.launch { pager.animateScrollToPage(i) }
                }.semantics { contentDescription = "手表$name" }, fontSize = 10.sp, color = muted)
            }
        }
        Text((if (cached) "缓存 · " else "$status · ") + displayClock(data.upstreamAt),
            Modifier.align(Alignment.TopCenter).offset(y = h * .87f).clickable(onClick = refresh)
                .semantics { contentDescription = "刷新手表快照" }, fontSize = 10.sp, color = if (cached) warning else muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WatchOverview(data: Snapshot) {
    var models by rememberSaveable { mutableStateOf(false) }
    Text("今日用量", fontSize = 9.sp, lineHeight = 10.sp, color = muted)
    Text(displayCompactTokens(data.today.tokens), fontSize = 21.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = bright)
    Text("预估 USD " + displayNumber(data.today.costUsd), fontSize = 9.sp, lineHeight = 10.sp, color = muted)
    val count = data.today.tokens?.toFloat()?.takeIf { it > 0f }
    val rows = data.today.tools.take(5)
    if (count != null && rows.isNotEmpty()) Canvas(Modifier.fillMaxWidth().height(3.dp)) {
        drawRect(track)
        var x = 0f
        for (row in rows) {
            val length = minOf(size.width - x, size.width * row.tokens.toFloat() / count)
            if (length <= 0f) break
            drawRect(hue(row.key), Offset(x, 0f), Size(length, size.height))
            x += length
        }
    }
    WatchRow("本月", displayCompactTokens(data.month.tokens) + " / USD " + displayNumber(data.month.costUsd))
    Text(if (models) "模型排行 ↔" else "工具排行 ↔", Modifier.clickable { models = !models }, fontSize = 9.sp, lineHeight = 10.sp, color = muted)
    (if (models) data.today.models else rows).take(5).forEach {
        WatchRow(it.label, displayCompactTokens(it.tokens), if (models) bright else hue(it.key))
    }
}

@Composable
private fun WatchProviderQuota(group: WatchQuotaGroup, freshness: Long, cached: Boolean, now: Instant, oled: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = 3.dp)
        .background(if (oled) Color(0xff121212) else panel, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(group.provider, color = hue(group.id), fontSize = 11.sp, lineHeight = 12.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        group.accounts.forEachIndexed { index, account ->
            val label = account.label ?: if (group.accounts.size > 1) "账号 ${index + 1}" else null
            label?.let { Text(it, color = muted, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1) }
            account.windows.forEach { row -> WatchQuotaWindow(row, cached || !fresh(row, freshness, now), now) }
        }
    }
}

@Composable
private fun WatchQuotaWindow(row: Reading, stale: Boolean, now: Instant) {
    Column(Modifier.fillMaxWidth()) {
        WatchRow(row.label,
            if (row.kind == "balance" || row.unlimited) row.value()
            else row.remainingPercent?.let { displayNumber(it) + "%" } ?: "—", hue(row.toolKey))
        Text(if (stale) "缓存" else countdown(row.boundaryAt, now),
            color = if (stale) warning else muted, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            drawRect(track)
            if (!stale && row.kind == "quota") drawRect(hue(row.toolKey),
                size = Size(size.width * ((row.remainingPercent ?: 0.0) / 100).toFloat(), size.height))
        }
    }
}

@Composable
private fun WatchTrend(data: Snapshot, defaults: WatchTrendSettings, maxGap: Long) {
    val ranges = Range.entries.filter { it != Range.TOTAL }
    var range by remember(defaults) { mutableStateOf(defaults.range.takeIf { it in ranges } ?: Range.LAST_7) }
    var intervals by remember(defaults) { mutableStateOf(defaults.intervals) }
    var style by remember(defaults) { mutableStateOf(defaults.style) }
    var cost by remember(defaults) { mutableStateOf(defaults.cost) }
    val choices = TrendInterval.forRange(range)
    val interval = intervals.forRange(range)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(range.label + " ↔", Modifier.clickable { range = ranges[(ranges.indexOf(range) + 1) % ranges.size] }
            .padding(3.dp).semantics { contentDescription = "趋势时间范围" }, fontSize = 10.sp, color = muted)
        Text(interval.label + " ↔", Modifier.clickable { intervals = intervals.withRange(range, choices[(choices.indexOf(interval) + 1) % choices.size]) }
            .padding(3.dp).semantics { contentDescription = "趋势显示间隔" }, fontSize = 10.sp, color = muted)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(style.label + " ↔", Modifier.clickable { style = if (style == TrendStyle.LINE) TrendStyle.CANDLE else TrendStyle.LINE }
            .padding(3.dp).semantics { contentDescription = "切换趋势图类型" }, fontSize = 10.sp, color = muted)
        Text(if (cost) "USD ↔" else "Tokens ↔",
            Modifier.clickable { cost = !cost }.padding(3.dp), fontSize = 10.sp, color = muted)
    }
    val series = remember(data, range, interval, maxGap) { data.trend(range, maxGapMinutes = maxGap.coerceIn(20, 120), interval = interval) }
    val values = series.points.map { it.value(cost) }
    val candles = if (style == TrendStyle.CANDLE) series.candles(cost) else emptyList()
    val peak = (if (style == TrendStyle.CANDLE) candles.maxOfOrNull { it.high } else values.filterNotNull().maxOrNull())
        ?.coerceAtLeast(1.0)
    if (peak == null) {
        Text(if (series.intraday) "等待连续采样" else "暂无趋势数据", fontSize = 10.sp, color = muted)
        return
    }
    Canvas(Modifier.fillMaxWidth().height(43.dp).semantics {
        contentDescription = style.label + " · " + range.label + " · " + interval.label
    }) {
        val pad = 2.dp.toPx()
        fun x(at: Float) = pad + (size.width - 2 * pad) * at
        fun y(value: Double) = pad + (size.height - 2 * pad) * (1 - (value / peak).toFloat())
        drawLine(track, Offset(pad, size.height - pad), Offset(size.width - pad, size.height - pad))
        if (style == TrendStyle.CANDLE) {
            candles.forEach { candle ->
                val cx = x(candle.position)
                val color = if (defaults.candleColors.usesGreen(candle.close >= candle.open)) Color(0xff91d789) else warning
                val top = y(maxOf(candle.open, candle.close))
                val bottom = y(minOf(candle.open, candle.close))
                val half = minOf(3.dp.toPx(), size.width * .022f)
                drawLine(color, Offset(cx, y(candle.high)), Offset(cx, y(candle.low)), 1.dp.toPx())
                drawRect(color, Offset(cx - half, top), Size(half * 2, (bottom - top).coerceAtLeast(1.dp.toPx())))
            }
        } else {
            var previous: Offset? = null
            series.points.zip(values).forEach { (point, value) ->
                val next = value?.let { Offset(x(point.position), y(it)) }
                if (next != null) {
                    previous?.let { drawLine(bright, it, next, 1.dp.toPx()) }
                    if (previous == null) drawCircle(bright, 1.dp.toPx(), next)
                }
                previous = next
            }
        }
    }
    WatchRow(series.start.takeLast(5), series.end.takeLast(5), muted)
}

@Composable
private fun WatchRow(name: String, value: String, color: Color = bright) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, Modifier.weight(1f), color = color, fontSize = 10.sp, lineHeight = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = bright, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 1)
    }
}

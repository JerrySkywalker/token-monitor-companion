package io.github.jerryskywalker.tmcomp.mobile

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.*
import kotlinx.serialization.Serializable

enum class OverviewCard(val title: String) {
    SUMMARY("用量与成本"), TREND("Tokens 趋势"), COMPOSITION("Token 组成"),
    TOOLS("我的工具"), QUOTAS("关注额度"), WALLETS("钱包余额"),
}

@Serializable
data class CardOption(val id: String, val visible: Boolean = true)

@Serializable
data class DashboardScope(val range: Range = Range.TODAY, val device: String? = null)

internal const val OVERVIEW_QUEUE_KEY = "overview_queue_v2"
internal fun cards(context: Context): List<OverviewCard> {
    val storage = DeviceStorage(context)
    storage.read<List<OverviewCard>>(OVERVIEW_QUEUE_KEY)?.let { return it.distinct() }
    val saved = storage.read<List<CardOption>>("overview_cards_v1").orEmpty()
    return migrateOverviewCards(saved)
}

internal fun migrateOverviewCards(saved: List<CardOption>): List<OverviewCard> {
    val known = OverviewCard.entries.associateBy { it.name }
    return (saved + OverviewCard.entries.map { CardOption(it.name) }).distinctBy { it.id }
        .filter { it.visible }.mapNotNull { known[it.id] }
}

internal fun Snapshot.period(scope: DashboardScope): Period? {
    val device = scope.device?.let { key -> collectors.firstOrNull { it.key == key } ?: return null }
    return when (scope.range) {
        Range.TODAY -> device?.today ?: today
        Range.WEEK -> if (device == null) week ?: observedPeriod(scope.range) else null
        Range.MONTH -> device?.month ?: month
        Range.TOTAL -> if (device == null) allTime else device.allTime
        else -> if (device == null) observedPeriod(scope.range) else null
    }
}

private fun Snapshot.observedPeriod(range: Range): Period {
    val days = daysFor(range)
    return Period(
        tokens = days.mapNotNull { it.tokens }.takeIf { it.isNotEmpty() }?.sum(),
        costUsd = days.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
    )
}
internal fun money(value: Double?) = value?.let { "USD " + displayNumber(it) } ?: "—"

internal data class ToolSummary(
    val id: String, val label: String, val usage: UsageEntry?, val readings: List<Reading>, val sources: List<ToolSource>,
) {
    fun availability(): String = when {
        readings.isNotEmpty() -> "已有额度读数"
        sources.any { it.status == "unauthorized" } -> "额度需要授权"
        sources.any { it.status == "disabled" } -> "额度采集已停用"
        sources.isNotEmpty() -> "额度暂未上报"
        else -> "额度未配置"
    }
}
private fun toolId(key: String) = when (key) {
    "github-copilot" -> "copilot"
    else -> key
}

internal fun tools(snapshot: Snapshot, period: Period?): List<ToolSummary> {
    val usage = period?.tools.orEmpty().groupBy { toolId(it.key) }
    val readings = snapshot.readings.groupBy { toolId(it.toolKey) }
    val sources = snapshot.toolSources.groupBy { toolId(it.id) }
    return (usage.keys + readings.keys + sources.keys).map { id ->
        val entries = usage[id].orEmpty()
        val rows = readings[id].orEmpty()
        val combined = entries.firstOrNull()?.copy(tokens = entries.sumOf { it.tokens }, costUsd =
            entries.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum())
        val label = when (id) {
            "codex" -> "Codex / ChatGPT"
            "antigravity" -> "Antigravity · AGY"
            "copilot" -> "GitHub Copilot"
            "cursor" -> "Cursor"
            "opencode" -> "OpenCode"
            else -> rows.firstOrNull()?.provider ?: sources[id]?.firstOrNull()?.label ?: combined?.label ?: id
        }
        ToolSummary(id, label, combined, rows, sources[id].orEmpty())
    }
}

internal fun components(period: Period?) = listOf(
    "非缓存输入" to period?.input, "缓存读取" to period?.cacheRead,
    "缓存写入" to period?.cacheWrite, "输出" to period?.output, "未分类" to period?.unclassified,
)

internal fun cardLines(card: OverviewCard, snapshot: Snapshot, scope: DashboardScope,
    favorites: List<FavoriteQuota>? = null, interval: TrendInterval = TrendInterval.NATIVE): List<Pair<String, String>> {
    val period = snapshot.period(scope)
    return when (card) {
        OverviewCard.SUMMARY -> listOf("Tokens" to displayNumber(period?.tokens), "预估成本" to money(period?.costUsd))
        OverviewCard.TREND -> snapshot.trend(scope, interval = interval).points.filter { it.tokens != null }.takeLast(3)
            .map { it.label.takeLast(5) to displayNumber(it.tokens) }
        OverviewCard.COMPOSITION -> components(period).map { (label, value) -> label to displayNumber(value) }
        OverviewCard.TOOLS -> tools(snapshot, period).take(5).map { it.label to displayNumber(it.usage?.tokens) }
        OverviewCard.QUOTAS -> snapshot.favoriteLines(favorites)
        OverviewCard.WALLETS -> snapshot.readings.filter { it.kind == "balance" }.take(4)
            .map { it.title() to if (it.stale) it.value() + " · 缓存" else it.value() }
    }
}

@Composable
internal fun ScopePicker(snapshot: Snapshot?, value: DashboardScope, change: (DashboardScope) -> Unit,
    pinnedRanges: List<Range> = defaultRanges,
    header: (@Composable RowScope.() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val choices = pinnedRanges.filter { it in rangeChoices }.distinct().take(4).ifEmpty { defaultRanges }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (header != null) header() else Spacer(Modifier.weight(1f))
            Box {
            TextButton(onClick = { open = true }) {
                Text(snapshot?.collectors?.firstOrNull { it.key == value.device }?.label ?: "所有设备")
            }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("所有设备") }, onClick = { change(value.copy(device = null)); open = false })
                snapshot?.collectors.orEmpty().forEach { device ->
                    DropdownMenuItem(text = { Text(device.label) }, onClick = { change(value.copy(device = device.key)); open = false })
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                choices.forEachIndexed { index, range ->
                    SegmentedButton(value.range == range, { change(value.copy(range = range)) },
                        SegmentedButtonDefaults.itemShape(index, choices.size),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary), icon = {},
                    ) { Text(range.label, maxLines = 1) }
                }
            }
            Box {
                IconButton({ more = true }, modifier = Modifier.semantics { contentDescription = "更多时间" }) {
                    Text("⋯", color = if (value.range in choices) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(more, { more = false }) {
                    (rangeChoices - choices.toSet()).forEach { range ->
                        DropdownMenuItem(text = { Text(range.label) }, onClick = { change(value.copy(range = range)); more = false })
                    }
                }
            }
        }
        if (value.range !in choices) Text("当前范围 · " + value.range.label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun LedgerCard(card: OverviewCard, snapshot: Snapshot, scope: DashboardScope,
    trendStyle: TrendStyle, interval: TrendInterval, favorites: List<FavoriteQuota>?,
    editFavorites: () -> Unit, detail: (OverviewCard, String?) -> Unit) {
    val period = snapshot.period(scope)
    val lines = cardLines(card, snapshot, scope, favorites, interval)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (card == OverviewCard.SUMMARY)
            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(card.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (card == OverviewCard.QUOTAS) TextButton(editFavorites) { Text("选择条目") }
                TextButton({ detail(card, null) }) {
                    Text(if (card == OverviewCard.TOOLS) "全部工具 ›" else "明细 ›")
                }
            }
            when (card) {
                OverviewCard.SUMMARY -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for ((label, value) in lines) Column(Modifier.weight(1f)) {
                            val main = if (label == "Tokens") displayCompactTokens(period?.tokens) else value
                            Text(main, Modifier.semantics { contentDescription = value }, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineSmall)
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (scope.device == null && scope.range in listOf(Range.LAST_7, Range.LAST_30, Range.LAST_365, Range.YEAR)) {
                        val days = snapshot.daysFor(scope.range)
                        Text("已记录 ${days.count { it.tokens != null }}/${days.size} 天", style = MaterialTheme.typography.labelSmall)
                    }
                }
                OverviewCard.TREND -> TrendPlot(snapshot.trend(scope, interval = interval), trendStyle, compact = true)
                OverviewCard.COMPOSITION -> {
                    val parts = components(period)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CompositionPlot(parts.map { it.second })
                            Text(displayCompactTokens(period?.tokens), style = MaterialTheme.typography.labelSmall)
                        }
                        Column(Modifier.weight(1f)) {
                            parts.filter { it.second != 0L }.forEach { (name, value) -> LedgerLine(name, displayNumber(value)) }
                        }
                    }
                }
                OverviewCard.TOOLS -> {
                    val rows = tools(snapshot, period)
                    if (rows.isEmpty()) Text("工具用量未上报")
                    rows.take(8).forEach { tool ->
                        val quota = tool.readings.firstOrNull { !it.stale && it.kind == "quota" && it.remainingPercent != null }
                        val value = displayNumber(tool.usage?.tokens) + (quota?.remainingPercent?.let {
                            " · " + displayNumber(it) + "%"
                        } ?: "")
                        LedgerLine(tool.label, value, Modifier.clickable { detail(card, tool.id) })
                    }
                }
                else -> if (lines.isEmpty()) Text("暂无读数") else lines.forEach { (name, value) -> LedgerLine(name, value) }
            }
        }
    }
}

@Composable
internal fun LedgerLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CompositionPlot(parts: List<Long?>) {
    val sum = parts.filterNotNull().sum().toFloat()
    val chartColors = listOf(Color(0xff087451), Color(0xff888cdb), Color(0xff519bb0), Color(0xff97c889), Color(0xff8d9897))
    val base = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(83.dp)) {
        val stroke = 11.dp.toPx()
        val offset = Offset(stroke / 2, stroke / 2)
        val inner = Size(size.width - stroke, size.height - stroke)
        drawArc(base, 0f, 360f, false, offset, inner, style = Stroke(stroke))
        if (sum > 0f) {
            var start = -90f
            parts.forEachIndexed { i, count ->
                val sweep = 360f * (count ?: 0) / sum
                drawArc(chartColors[i], start, sweep, false, offset, inner, style = Stroke(stroke))
                start += sweep
            }
        }
    }
}

@Composable
internal fun ToolDetail(row: ToolSummary, period: Period?, freshnessMs: Long, stale: Boolean) {
    Text(row.label, style = MaterialTheme.typography.headlineSmall)
    LedgerLine("Tokens", displayNumber(row.usage?.tokens))
    LedgerLine("预估成本", money(row.usage?.costUsd))
    if (row.readings.isEmpty() && row.sources.isEmpty()) Text(row.availability())
    period?.toolModels.orEmpty().filterKeys { toolId(it) == row.id }.values.flatten().take(20)
        .forEach { LedgerLine(it.label, displayNumber(it.tokens)) }
    quotaAccounts(row.readings, row.sources).forEach { QuotaAccountCard(it, freshnessMs, stale) }
}

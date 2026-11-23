package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.*
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun ActivityDevicePicker(snapshot: Snapshot?, device: String?, onDevice: (String?) -> Unit) {
    val devices = snapshot?.collectors.orEmpty()
    val options: List<String?> = listOf(null) + devices.map { it.key }
    val label: (String?) -> String = { key -> devices.firstOrNull { it.key == key }?.label ?: "所有设备" }
    CompactChoice(label(device), options, label, onDevice)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityScreen(snapshot: Snapshot?, scope: DashboardScope, pinned: List<Range>,
    style: TrendStyle, interval: TrendInterval, colors: CandleColors, onScope: (DashboardScope) -> Unit,
    onStyle: (TrendStyle) -> Unit, onInterval: (TrendInterval) -> Unit, onTool: (String) -> Unit) {
    var cost by rememberSaveable { mutableStateOf(false) }
    val now by produceState(Instant.now(), scope.range) {
        while (true) {
            value = Instant.now()
            delay(60_000)
        }
    }
    val common = pinned.filter { it in rangeChoices }
    val extra = (Range.entries.filter { it.rollingHours > 0 } + rangeChoices.filterNot { it in common })
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                common.forEach { range -> FilterChip(scope.range == range, { onScope(scope.copy(range = range)) }, { Text(range.label) }) }
            }
            CompactChoice(if (scope.range in common) "更多时间" else scope.range.label, extra, { it.label }) { onScope(scope.copy(range = it)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactChoice(style.label, TrendStyle.entries, { it.label }, onStyle)
            CompactChoice(if (cost) "预估成本" else "Tokens", listOf(false, true), { if (it) "预估成本" else "Tokens" }) { cost = it }
            CompactChoice("间隔 · ${interval.label}", TrendInterval.forRange(scope.range), { it.label }, onInterval)
        }
    }
    snapshot?.let { data ->
        val period = remember(data, scope, now) {
            if (scope.range.rollingHours > 0) data.hourlyUsage(scope.range, scope.device, now) else data.period(scope)
        }
        val series = remember(data, scope, interval, now) { data.trend(scope, interval = interval, now = now) }
        TrendPlot(series, style, cost, candleColors = colors)
        Text("工具分布 · Tokens", style = MaterialTheme.typography.titleMedium)
        val rows = tools(data, period).filter { it.usage != null }.sortedByDescending { it.usage?.tokens }
        if (rows.isEmpty()) Text("所选范围暂无工具用量", style = MaterialTheme.typography.bodySmall)
        rows.forEach { tool ->
            val tokens = requireNotNull(tool.usage).tokens
            val fraction = period?.tokens?.takeIf { it > 0 }?.let { tokens.toFloat() / it }
            Column(Modifier.fillMaxWidth().clickable { onTool(tool.id) }.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                LedgerLine(tool.label, displayNumber(tokens))
                fraction?.let { ShareBar(it) }
            }
        }
    }
}

@Composable
private fun <T> CompactChoice(title: String, choices: List<T>, label: (T) -> String, select: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, contentPadding = PaddingValues(horizontal = 7.dp)) {
            Text("$title ▾", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 162.dp))
        }
        DropdownMenu(open, { open = false }) {
            choices.forEach { choice -> DropdownMenuItem({ Text(label(choice)) }, { open = false; select(choice) }) }
        }
    }
}

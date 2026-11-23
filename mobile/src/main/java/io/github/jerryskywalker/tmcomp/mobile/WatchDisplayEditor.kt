package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.*

internal const val WATCH_DISPLAY_PRESET_KEY = "watch_display_preset_v1"

@Composable
internal fun WatchDisplayEditor(initial: WatchDisplay, readings: List<Reading>, snapshot: Snapshot?,
    busy: Boolean, onSave: (WatchDisplay) -> Unit, onScan: ((WatchDisplay) -> Unit)? = null) {
    var display by remember(initial) { mutableStateOf(initial) }
    var category by rememberSaveable { mutableStateOf(WatchQuotaCategory.CYCLE) }
    var previewTab by rememberSaveable { mutableStateOf(false) }
    val trend = display.trend
    val interval = trend.intervals.forRange(trend.range)
    val groups = watchQuotaGroups(readings, category, display.quotaOrders[category].orEmpty())
    val ranges = Range.entries.filter { it != Range.TOTAL }

    SettingsHeading("手表实时预览")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!previewTab, { previewTab = false }, { Text("额度预览") })
        FilterChip(previewTab, { previewTab = true }, { Text("趋势预览") })
    }
    WatchDisplayPreview(snapshot, display, category, groups, previewTab)
    if (previewTab) Text("趋势预览使用手机当前快照。独立直连的手表使用自己的取数记录。",
        style = MaterialTheme.typography.bodySmall)

    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("手表 OLED 纯黑", Modifier.weight(1f))
            Switch(display.oled, { display = display.copy(oled = it) })
        }
        ChoiceSetting("默认趋势范围", trend.range, ranges, { it.label }) {
            display = display.copy(trend = trend.copy(range = it))
            previewTab = true
        }
        ChoiceSetting("趋势显示间隔", interval, TrendInterval.forRange(trend.range), { it.label }) {
            display = display.copy(trend = trend.copy(intervals = trend.intervals.withRange(trend.range, it)))
            previewTab = true
        }
        ChoiceSetting("图表类型", trend.style, TrendStyle.entries, { it.label }) {
            display = display.copy(trend = trend.copy(style = it))
            previewTab = true
        }
        ChoiceSetting("K 线涨跌颜色", trend.candleColors, CandleColors.entries, { it.label }) {
            display = display.copy(trend = trend.copy(candleColors = it))
            previewTab = true
        }
        ChoiceSetting("趋势指标", trend.cost, listOf(false, true), { if (it) "预估成本" else "Tokens" }) {
            display = display.copy(trend = trend.copy(cost = it))
            previewTab = true
        }
    }
    SettingsHeading("分类内的工具顺序")
    ChoiceSetting("额度分类", category, WatchQuotaCategory.entries, { it.label }) {
        category = it
        previewTab = false
    }
    if (groups.isEmpty()) Text("此分类暂无读数。")
    ReorderRows(groups.map { ReorderEntry(it.id, it.provider) }, { from, to ->
        val next = groups.map { it.id }.move(from, to)
        val prior = display.quotaOrders[category].orEmpty()
        display = display.copy(quotaOrders = display.quotaOrders + (category to (next + prior).distinct().take(80)))
        previewTab = false
    })
    Button({ onSave(display) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("保存手表显示设置") }
    if (onScan != null) {
        Text("独立手表可在连接设置打开「从手机导入显示设置」。两端连接同一 Wi‑Fi。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ onScan(display) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("扫码发送显示设置") }
    }
}

@Composable
private fun WatchDisplayPreview(snapshot: Snapshot?, display: WatchDisplay, category: WatchQuotaCategory,
    groups: List<WatchQuotaGroup>, trendPage: Boolean) {
    val trend = display.trend
    val background = if (display.oled) Color.Black else Color(0xff05070a)
    MaterialTheme(colorScheme = if (display.oled) oledColors else nightColors) {
        Box(Modifier.fillMaxWidth().height(218.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.size(218.dp).clip(CircleShape), color = background) {
                Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (trendPage) "趋势" else category.label, style = MaterialTheme.typography.titleMedium)
                    if (trendPage && snapshot != null) {
                        val interval = trend.intervals.forRange(trend.range)
                        Text(trend.range.label + " · " + interval.label + " · " + trend.style.label,
                            style = MaterialTheme.typography.labelSmall)
                        TrendPlot(snapshot.trend(trend.range, interval = interval), trend.style, trend.cost, compact = true,
                            candleColors = trend.candleColors)
                    } else if (trendPage || groups.isEmpty()) {
                        Text("等待快照", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            groups.forEach { group ->
                                Column(Modifier.fillMaxWidth().background(
                                    if (display.oled) Color(0xff121212) else Color(0xff131a25), MaterialTheme.shapes.small)
                                    .padding(4.dp)) {
                                    Text(group.provider, style = MaterialTheme.typography.labelMedium)
                                    group.accounts.forEach { account ->
                                        account.label?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                        account.windows.forEach {
                                            Text(it.label + " · " + it.value(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

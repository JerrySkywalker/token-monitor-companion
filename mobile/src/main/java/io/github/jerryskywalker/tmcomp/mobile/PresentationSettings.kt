package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.*
import kotlinx.serialization.Serializable

internal const val THEME_KEY = "phone_theme_v1"
internal const val FAVORITE_QUOTAS_KEY = "favorite_quotas_v1"
internal const val FAVORITE_QUOTA_DISPLAY = "favorite_quota_display_v1"
internal const val COLLECTOR_DISPLAY = "collector_display_v1"

internal enum class AppTheme(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色"), OLED("OLED 纯黑") }

internal val nightColors = darkColorScheme(
    primary = Color(0xff94d8bc), onPrimary = Color(0xff10392c), primaryContainer = Color(0xff1c4234),
    onPrimaryContainer = Color(0xffb7efdb), background = Color(0xff101816), surface = Color(0xff17221f),
    onBackground = Color(0xffe0ece6), onSurface = Color(0xffe0ece6), outline = Color(0xff60796e),
    outlineVariant = Color(0xff394e45), surfaceVariant = Color(0xff263831), onSurfaceVariant = Color(0xffb8ccbf),
    secondaryContainer = Color(0xff264438), onSecondaryContainer = Color(0xffd5ede1),
)

internal val oledColors = nightColors.copy(
    background = Color.Black, surface = Color(0xff0d0d0d), surfaceVariant = Color(0xff181818),
    primaryContainer = Color(0xff12251d), outlineVariant = Color(0xff363636),
)

internal fun AppTheme.colors(systemDark: Boolean): ColorScheme = when (this) {
    AppTheme.SYSTEM -> if (systemDark) nightColors else ledgerColors
    AppTheme.LIGHT -> ledgerColors
    AppTheme.DARK -> nightColors
    AppTheme.OLED -> oledColors
}

@Composable
internal fun AppTheme.colors(): ColorScheme = colors(isSystemInDarkTheme())

@Serializable
internal data class FavoriteQuota(val id: String, val label: String)

internal enum class QuotaDisplayStyle(val label: String) { BAR("文字和数据条"), TEXT("纯文字") }
internal enum class QuotaMetric(val label: String) { REMAINING("剩余"), USED("已用") }

@Serializable
internal data class FavoriteQuotaDisplay(
    val style: QuotaDisplayStyle = QuotaDisplayStyle.BAR,
    val metric: QuotaMetric = QuotaMetric.REMAINING,
)

internal data class FavoriteQuotaLine(val label: String, val value: String, val fill: Float?)

internal fun Snapshot.followedQuotas(): List<FavoriteQuota> = readings.filter { it.kind == "quota" }
    .sortedBy { it.remainingPercent ?: 101.0 }.take(4).map { FavoriteQuota(it.id, it.title()) }

internal fun Snapshot.favoriteLines(selected: List<FavoriteQuota>?, display: FavoriteQuotaDisplay): List<FavoriteQuotaLine> {
    val quota = readings.filter { it.kind == "quota" }.associateBy { it.id }
    return (selected ?: followedQuotas()).map { item ->
        val reading = quota[item.id]
        val remaining = reading?.takeUnless { it.unlimited }?.let { row ->
            row.remainingPercent ?: row.limit?.takeIf { it > 0 }?.let { cap -> row.amount?.let { it / cap * 100 } }
        }?.coerceIn(0.0, 100.0)
        val percent = remaining?.let { if (display.metric == QuotaMetric.USED) 100 - it else it }
        val value = when {
            reading == null -> "暂未上报"
            percent != null -> "${display.metric.label} ${displayNumber(percent)}%"
            reading.unlimited || display.metric == QuotaMetric.REMAINING -> reading.value()
            else -> "已用未知"
        }
        FavoriteQuotaLine(reading?.title() ?: item.label, value + if (reading?.stale == true) " · 缓存" else "",
            percent?.toFloat()?.div(100)?.takeIf { display.style == QuotaDisplayStyle.BAR })
    }
}

@Composable
internal fun EditFavoriteQuotas(snapshot: Snapshot?, favorites: List<FavoriteQuota>?, initial: FavoriteQuotaDisplay,
    save: (List<FavoriteQuota>, FavoriteQuotaDisplay) -> Unit) {
    var draft by rememberSaveable {
        mutableStateOf(ArrayList((favorites ?: snapshot?.followedQuotas().orEmpty()).map { it.id }))
    }
    val rows = snapshot?.readings.orEmpty().filter { it.kind == "quota" }
    val labels = (favorites.orEmpty().associate { it.id to it.label }) + rows.associate { it.id to it.title() }
    var filter by rememberSaveable { mutableStateOf("") }
    var style by rememberSaveable { mutableStateOf(initial.style) }
    var metric by rememberSaveable { mutableStateOf(initial.metric) }
    SettingsHeading("关注额度")
    SettingsGroup {
        ChoiceSetting("显示效果", style, QuotaDisplayStyle.entries, { it.label }) { style = it }
        SettingsDivider()
        ChoiceSetting("显示口径", metric, QuotaMetric.entries, { it.label }) { metric = it }
    }
    val selection = draft.map { FavoriteQuota(it, labels[it] ?: "暂未上报") }
    val display = FavoriteQuotaDisplay(style, metric)
    snapshot?.favoriteLines(selection, display)?.firstOrNull()?.let { line ->
        SettingsHeading("预览")
        FavoriteQuotaRow(line)
    }
    if (draft.isEmpty()) Text("从下方添加要显示的额度窗口。")
    ReorderRows(draft.map { ReorderEntry(it, labels[it] ?: "暂未上报", if (rows.none { row -> row.id == it }) "暂未上报" else null) },
        { from, to -> draft = ArrayList(draft.move(from, to)) }, { id -> draft = ArrayList(draft.filterNot { it == id }) })
    OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("搜索工具、账号或额度") }, singleLine = true)
    rows.filter { it.id !in draft && it.title().contains(filter.trim(), ignoreCase = true) }
        .groupBy { it.provider }.forEach { (provider, matches) ->
            SettingsHeading(provider)
            SettingsGroup {
                matches.forEach { reading ->
                    ListItem(headlineContent = { Text(reading.title()) },
                        trailingContent = { TextButton({ draft = ArrayList(draft + reading.id) }) { Text("添加") } })
                }
            }
        }
    Button({ save(selection, display) }, Modifier.fillMaxWidth()) { Text("保存关注额度") }
}

@Composable
internal fun FavoriteQuotaRow(line: FavoriteQuotaLine, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LedgerLine(line.label, line.value, compact = compact)
        line.fill?.let { ShareBar(it) }
    }
}

@Composable
internal fun ShareBar(fill: Float) {
    LinearProgressIndicator(progress = { fill.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(4.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {})
}

@Composable
internal fun <T> ChoiceSetting(title: String, selected: T, choices: List<T>, label: (T) -> String,
    update: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(title, value = label(selected), onClick = { open = true })
        DropdownMenu(open, { open = false }) {
            choices.forEach { item ->
                DropdownMenuItem(text = { Text(label(item)) }, onClick = { open = false; update(item) })
            }
        }
    }
}

@Composable
internal fun DevicesTab(snapshot: Snapshot?, display: CollectorDisplay, onDevice: (String) -> Unit, onWear: () -> Unit) {
    SettingsGroup { SettingRow("管理 Wear OS 手表", icon = R.drawable.settings_watch, onClick = onWear) }
    SettingsHeading("Hub 采集设备")
    if (snapshot?.collectors.isNullOrEmpty()) Text("Hub 暂未上报设备。")
    orderedCollectors(snapshot, display.order).forEach { device ->
        val cached = device.stale || locallyStale(device.updatedAt, snapshot?.freshnessMs ?: 0)
        SettingsGroup {
            if (display.layout == CollectorLayout.COMPACT) {
                TextButton({ onDevice(device.key) }, Modifier.fillMaxWidth()) {
                    Text(device.label, Modifier.weight(1f), maxLines = 1)
                    Text("今日 ${displayCompactTokens(device.today.tokens)} · 月 ${displayCompactTokens(device.month.tokens)}${if (cached) " · 缓存" else ""}",
                        style = MaterialTheme.typography.labelSmall)
                }
            } else {
                SettingRow(device.label, summary = (if (cached) "缓存 · " else "上报 · ") + displayClock(device.updatedAt),
                    value = displayCompactTokens(device.today.tokens), onClick = { onDevice(device.key) })
                SettingsDivider()
                SettingRow("本月用量", value = displayCompactTokens(device.month.tokens))
            }
        }
    }
}

internal enum class CollectorLayout(val label: String) { STANDARD("普通"), COMPACT("紧凑") }

@Serializable
internal data class CollectorDisplay(val layout: CollectorLayout = CollectorLayout.STANDARD, val order: List<String> = emptyList())

private fun orderedCollectors(snapshot: Snapshot?, order: List<String>): List<Collector> {
    val positions = order.withIndex().associate { it.value to it.index }
    return snapshot?.collectors.orEmpty().sortedBy { positions[it.key] ?: Int.MAX_VALUE }
}

@Composable
internal fun CollectorSettings(snapshot: Snapshot?, display: CollectorDisplay, update: (CollectorDisplay) -> Unit) {
    SettingsGroup {
        ChoiceSetting("显示布局", display.layout, CollectorLayout.entries, { it.label }) { update(display.copy(layout = it)) }
    }
    SettingsHeading("采集设备顺序")
    val devices = orderedCollectors(snapshot, display.order)
    if (devices.isEmpty()) Text("Hub 暂未上报设备。")
    ReorderRows(devices.map { ReorderEntry(it.key, it.label) }, { from, to ->
        val next = devices.map { it.key }.move(from, to)
        update(display.copy(order = (next + display.order).distinct()))
    })
}

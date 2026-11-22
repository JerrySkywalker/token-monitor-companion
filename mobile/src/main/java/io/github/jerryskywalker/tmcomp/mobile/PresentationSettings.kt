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

internal fun Snapshot.followedQuotas(): List<FavoriteQuota> = readings.filter { it.kind == "quota" }
    .sortedBy { it.remainingPercent ?: 101.0 }.take(4).map { FavoriteQuota(it.id, it.title()) }

internal fun Snapshot.favoriteLines(selected: List<FavoriteQuota>?): List<Pair<String, String>> {
    val quota = readings.filter { it.kind == "quota" }.associateBy { it.id }
    return (selected ?: followedQuotas()).map { item ->
        val reading = quota[item.id]
        val value = reading?.let { it.value() + if (it.stale) " · 缓存" else "" } ?: "暂未上报"
        (reading?.title() ?: item.label) to value
    }
}

@Composable
internal fun EditFavoriteQuotas(snapshot: Snapshot?, favorites: List<FavoriteQuota>?, save: (List<FavoriteQuota>) -> Unit) {
    var draft by rememberSaveable {
        mutableStateOf(ArrayList((favorites ?: snapshot?.followedQuotas().orEmpty()).map { it.id }))
    }
    val rows = snapshot?.readings.orEmpty().filter { it.kind == "quota" }
    val labels = (favorites.orEmpty().associate { it.id to it.label }) + rows.associate { it.id to it.title() }
    var filter by rememberSaveable { mutableStateOf("") }
    SettingsHeading("关注额度")
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
    Button({ save(draft.map { FavoriteQuota(it, labels[it] ?: "暂未上报") }) }, Modifier.fillMaxWidth()) { Text("保存关注额度") }
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
internal fun DevicesTab(snapshot: Snapshot?, onDevice: (String) -> Unit, onWear: () -> Unit) {
    Text("设备", style = MaterialTheme.typography.headlineMedium)
    SettingsGroup { SettingRow("管理 Wear OS 手表", icon = R.drawable.settings_watch, onClick = onWear) }
    SettingsHeading("Hub 采集设备")
    if (snapshot?.collectors.isNullOrEmpty()) Text("Hub 暂未上报设备。")
    snapshot?.collectors.orEmpty().forEach { device ->
        val cached = device.stale || locallyStale(device.updatedAt, snapshot?.freshnessMs ?: 0)
        SettingsGroup {
            SettingRow(device.label, summary = (if (cached) "缓存 · " else "上报 · ") + displayClock(device.updatedAt),
                value = displayCompactTokens(device.today.tokens), onClick = { onDevice(device.key) })
            SettingsDivider()
            SettingRow("本月用量", value = displayCompactTokens(device.month.tokens))
        }
    }
}

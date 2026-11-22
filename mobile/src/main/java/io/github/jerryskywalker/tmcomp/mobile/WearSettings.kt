package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.Mode
import io.github.jerryskywalker.tmcomp.core.Reading
import io.github.jerryskywalker.tmcomp.core.WatchConfig
import io.github.jerryskywalker.tmcomp.core.WatchStatus
import io.github.jerryskywalker.tmcomp.core.displayClock
import io.github.jerryskywalker.tmcomp.core.title

@Composable
internal fun WearPairingEntry(busy: Boolean, dataLayerUnavailable: Boolean, onScan: () -> Unit) {
    SettingsHeading("添加手表")
    SettingsGroup {
        SettingRow("扫描手表配对码", icon = R.drawable.settings_watch,
            summary = "在同一 Wi‑Fi 下，将手机的 Hub 配置导入手表以独立使用。", enabled = !busy, onClick = onScan)
    }
    if (dataLayerUnavailable) {
        Text("手表系统同步暂不可用。扫码仍可导入直连配置。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WearDevices(
    watches: List<WatchConfig>,
    available: List<DiscoveredWatch>,
    busy: Boolean,
    showBoundList: Boolean,
    onOpen: (WatchConfig) -> Unit,
    onDiscover: () -> Unit,
    onBind: (DiscoveredWatch) -> Unit,
) {
    if (showBoundList && watches.isNotEmpty()) {
        SettingsHeading("已绑定手表")
        SettingsGroup {
            watches.forEachIndexed { index, watch ->
                if (index > 0) SettingsDivider()
                SettingRow(watch.label, icon = R.drawable.settings_watch, onClick = { onOpen(watch) })
            }
        }
    }
    if (available.isNotEmpty()) {
        SettingsHeading("可绑定手表")
        SettingsGroup {
            available.forEachIndexed { index, device ->
                if (index > 0) SettingsDivider()
                SettingRow(device.label, icon = R.drawable.settings_watch, value = "绑定手表", enabled = !busy,
                    onClick = { onBind(device) })
            }
        }
    }
    if (watches.isEmpty() && available.isEmpty()) {
        SettingsGroup {
            Text("在已配对的 Wear OS 手表上打开 Token Monitor。", Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    TextButton(onDiscover, enabled = !busy) { Text("查找手表") }
}

@Composable
internal fun WatchSettings(
    watch: WatchConfig,
    connected: Boolean,
    readings: List<Reading>,
    busy: Boolean,
    onChange: (WatchConfig) -> Unit,
    onHub: () -> Unit,
    onRemove: () -> Unit,
) {
    var pinOpen by remember(watch.id) { mutableStateOf(false) }
    var rateOpen by remember(watch.id) { mutableStateOf(false) }
    var ringOpen by remember(watch.id) { mutableStateOf(false) }
    val report = watch.report?.takeIf { it.appliedVersion == watch.version && it.mode == watch.mode }
    val status = when (report?.state) {
        WatchStatus.READY -> "已同步"
        WatchStatus.PAUSED -> "已暂停"
        WatchStatus.NEEDS_PAIRING -> "等待 Hub 设置"
        WatchStatus.WAITING -> "等待数据"
        WatchStatus.OFFLINE -> "更新失败"
        null -> "待手表确认"
    }
    val colors = MaterialTheme.colorScheme

    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides colors.onPrimary) {
                    SettingsIcon(R.drawable.settings_watch)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(watch.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                report?.snapshotAt?.let {
                    Text("最近同步 " + displayClock(it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                val phoneLink = if (connected) "手机连接在线" else "手机连接离线"
                Text("配置 v" + watch.version + " · " + phoneLink,
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            SettingsStatus(status, report?.state == WatchStatus.READY)
        }
    }

    Spacer(Modifier.height(4.dp))
    SettingsHeading("同步方式")
    SettingsGroup {
        Column(Modifier.selectableGroup()) {
            WatchModeChoice("手机推送", "由手机从 Hub 取数后转发用量",
                selected = watch.mode == Mode.PHONE, enabled = !busy) {
                if (watch.mode != Mode.PHONE) onChange(watch.copy(mode = Mode.PHONE))
            }
            SettingsDivider()
            WatchModeChoice("独立直连", "手表直接从配置的 Token Monitor Hub 取数",
                selected = watch.mode == Mode.DIRECT, enabled = !busy) {
                if (watch.mode != Mode.DIRECT) onChange(watch.copy(mode = Mode.DIRECT))
            }
        }
    }

    SettingsGroup {
        SettingRow("手表 Hub 直连设置", summary = watch.report?.hubOrigin ?: "地址和 Secret",
            enabled = !busy, onClick = onHub)
        SettingsDivider()
        ListItem(headlineContent = { Text("暂停用量同步", fontWeight = FontWeight.Medium) },
            trailingContent = {
                Switch(checked = watch.paused, onCheckedChange = { onChange(watch.copy(paused = it)) }, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "暂停用量同步" })
            }, colors = ListItemDefaults.colors(containerColor = colors.surface))
        SettingsDivider()
        Box {
            val pin = readings.find { it.id == watch.pinnedId }?.title()
            SettingRow("置顶额度", summary = pin,
                value = when {
                    watch.pinnedId == null -> "自动"
                    pin == null -> "上游暂缺"
                    else -> null
                }, enabled = !busy, onClick = { pinOpen = true })
            DropdownMenu(pinOpen, onDismissRequest = { pinOpen = false }) {
                DropdownMenuItem(text = { Text("自动选择") }, onClick = {
                    pinOpen = false
                    onChange(watch.copy(pinnedId = null))
                })
                readings.forEach { row ->
                    DropdownMenuItem(text = { Text(row.title()) }, onClick = {
                        pinOpen = false
                        onChange(watch.copy(pinnedId = row.id))
                    })
                }
            }
        }
        SettingsDivider()
        Box {
            val providers = readings.filter { it.kind == "quota" }.distinctBy { it.toolKey }
            val selected = when (watch.ringProvider) {
                null -> "跟随置顶"
                "" -> "关闭"
                else -> providers.find { it.toolKey == watch.ringProvider }?.provider ?: watch.ringProvider
            }
            SettingRow("外圈来源", value = selected, enabled = !busy, onClick = { ringOpen = true })
            DropdownMenu(ringOpen, onDismissRequest = { ringOpen = false }) {
                val choices = listOf(null to "跟随置顶", "" to "关闭") + providers.map { it.toolKey to it.provider }
                choices.forEach { (key, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        ringOpen = false
                        onChange(watch.copy(ringProvider = key))
                    })
                }
            }
        }
    }

    SettingsGroup {
        Box {
            SettingRow("独立直连刷新间隔", value = watch.refreshMinutes.toString() + " 分钟", enabled = !busy,
                onClick = { rateOpen = true })
            DropdownMenu(rateOpen, onDismissRequest = { rateOpen = false }) {
                listOf(15L, 30L, 60L).forEach { minutes ->
                    DropdownMenuItem(text = { Text(minutes.toString() + " 分钟") }, onClick = {
                        rateOpen = false
                        onChange(watch.copy(refreshMinutes = minutes))
                    })
                }
            }
        }
    }
    OutlinedButton({ onChange(watch.copy(refreshRequest = watch.refreshRequest + 1)) }, Modifier.fillMaxWidth(),
        enabled = !busy && !watch.paused) {
        SettingsIcon(R.drawable.settings_refresh)
        Spacer(Modifier.width(8.dp))
        Text("请求刷新")
    }
    TextButton(onRemove, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
        Text("移除手表")
    }
}

@Composable
private fun WatchModeChoice(title: String, summary: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

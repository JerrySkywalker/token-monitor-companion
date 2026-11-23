package io.github.jerryskywalker.tmcomp.mobile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jerryskywalker.tmcomp.core.Snapshot
import io.github.jerryskywalker.tmcomp.core.CandleColors
import io.github.jerryskywalker.tmcomp.core.displayClock

@Composable
internal fun SettingsHome(
    origin: String,
    reachable: Boolean?,
    quotaLayout: QuotaLayout,
    overviewLayout: OverviewLayout,
    onOverviewLayout: (OverviewLayout) -> Unit,
    watchCount: Int,
    onServer: () -> Unit,
    onOverview: () -> Unit,
    onRanges: () -> Unit,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    onFavorites: () -> Unit,
    onQuotaLayout: () -> Unit,
    candleColors: CandleColors,
    onCandleColors: (CandleColors) -> Unit,
    collectorLayout: CollectorLayout,
    onCollectors: () -> Unit,
    onWidget: () -> Unit,
    onWear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PageTitle("设置")
        ServerConnectionCard(origin.substringAfter("://"), reachable, onServer)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsHeading("显示与桌面")
            SettingsGroup {
                ChoiceSetting("外观", theme, AppTheme.entries, { it.label }, onTheme)
                SettingsDivider()
                ChoiceSetting("总览布局", overviewLayout, OverviewLayout.entries, { it.label }, onOverviewLayout)
                SettingsDivider()
                SettingRow("编辑总览", "展示队列和卡片顺序", R.drawable.settings_grid, onClick = onOverview)
                SettingsDivider()
                SettingRow("关注额度", "选择具体工具、账号和限制窗口", R.drawable.nav_quota, onClick = onFavorites)
                SettingsDivider()
                SettingRow("总览时间筛选", "最多固定 4 个常用范围", R.drawable.settings_grid, onClick = onRanges)
                SettingsDivider()
                SettingRow("额度页布局", icon = R.drawable.settings_layout, value = quotaLayout.label, onClick = onQuotaLayout)
                SettingsDivider()
                ChoiceSetting("K 线涨跌颜色", candleColors, CandleColors.entries, { it.label }, onCandleColors)
                SettingsDivider()
                SettingRow("桌面小工具", "添加到主屏幕", R.drawable.settings_widget, onClick = onWidget)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsHeading("设备")
            SettingsGroup {
                SettingRow("Hub 采集设备", "布局和排序", R.drawable.settings_layout, value = collectorLayout.label, onClick = onCollectors)
                SettingsDivider()
                SettingRow("Wear OS", if (watchCount == 0) "暂无已绑定手表" else "$watchCount 台已绑定",
                    R.drawable.settings_watch, onClick = onWear)
            }
        }
    }
}

@Composable
internal fun PageTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 30.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ),
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { PageTitle(title) },
        navigationIcon = {
            IconButton(onBack) { SettingsIcon(R.drawable.settings_back, "返回") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
internal fun SettingsIcon(@DrawableRes icon: Int, description: String? = null) {
    Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp))
}

@Composable
internal fun SettingsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SettingRow(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        modifier = if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick),
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = icon?.let { { SettingsIcon(it) } },
        trailingContent = if (value == null && onClick == null) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    value?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                    if (onClick != null) SettingsIcon(R.drawable.settings_forward)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
internal fun SettingsStatus(label: String, healthy: Boolean) {
    val color = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun ServerConnectionCard(host: String, reachable: Boolean?, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val status = when (reachable) {
        true -> "已连接"
        false -> "连接失败"
        null -> "等待检查"
    }
    val cardContent: @Composable ColumnScope.() -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIcon(R.drawable.settings_server)
            Column(Modifier.weight(1f)) {
                Text("Token Monitor Hub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(host, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            SettingsStatus(status, reachable == true)
            if (onClick != null) SettingsIcon(R.drawable.settings_forward)
        }
    }
    val cardColors = CardDefaults.outlinedCardColors(containerColor = colors.primaryContainer.copy(alpha = .58f))
    val border = BorderStroke(1.dp, colors.outlineVariant)
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) OutlinedCard(onClick, Modifier.fillMaxWidth(), shape = shape, colors = cardColors, border = border, content = cardContent)
    else OutlinedCard(Modifier.fillMaxWidth(), shape = shape, colors = cardColors, border = border, content = cardContent)
}

@Composable
internal fun ServerSettings(
    origin: String,
    reachable: Boolean?,
    snapshot: Snapshot?,
    stale: Boolean,
    usageError: String?,
    busy: Boolean,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ServerConnectionCard(origin, reachable)
        SettingsHeading("Hub 数据")
        SettingsGroup {
            SettingRow("数据状态", value = when {
                snapshot == null -> "等待首份快照"
                stale -> "缓存"
                else -> "最新"
            })
            SettingsDivider()
            SettingRow("Hub 更新时间", value = displayClock(snapshot?.upstreamAt))
            SettingsDivider()
            SettingRow("本机取数时间", value = displayClock(snapshot?.retrievedAt))
        }
        usageError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onCheck, Modifier.fillMaxWidth(), enabled = !busy) {
            SettingsIcon(R.drawable.settings_refresh)
            Spacer(Modifier.width(8.dp))
            Text("检查连接")
        }
        SettingsGroup { SettingRow("编辑 Hub 地址和 Secret", enabled = !busy, onClick = onEdit) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClear, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text("清除本机 Hub")
        }
    }
}

@Composable
internal fun HubForm(startAddress: String, busy: Boolean, submit: String, onSubmit: (String, String) -> Unit) {
    var address by remember(startAddress) { mutableStateOf(startAddress) }
    var secret by remember(startAddress) { mutableStateOf("") }
    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(),
        label = { Text("Hub 地址") }, singleLine = true, enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(),
        label = { Text("Hub Secret") }, singleLine = true, enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    Button(onClick = {
        onSubmit(address, secret)
        secret = ""
    }, enabled = !busy && address.isNotBlank() && secret.isNotBlank()) { Text(submit) }
}

@Composable
internal fun QuotaLayoutSettings(
    layout: QuotaLayout,
    snapshot: Snapshot?,
    stale: Boolean,
    onLayout: (QuotaLayout) -> Unit,
) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuotaLayout.entries.forEach { option ->
            QuotaLayoutChoice(option, layout == option, Modifier.weight(1f)) { onLayout(option) }
        }
    }
    Spacer(Modifier.height(6.dp))
    SettingsHeading("预览")
    val accounts = snapshot?.let { quotaAccounts(it.readings, it.toolSources) }.orEmpty()
        .mapNotNull { it.forKind("quota") }
        .filter { account -> account.readings.any { it.kind == "quota" } }
        .sortedBy {
            when (it.providerId) {
                "codex" -> 0
                "copilot", "github-copilot" -> 1
                else -> 2
            }
        }
        .distinctBy { it.providerId }
        .take(2)
    if (snapshot == null || accounts.isEmpty()) {
        SettingsGroup {
            Text("收到 Hub 额度后显示预览", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(if (layout == QuotaLayout.COMPACT) 5.dp else 10.dp)) {
            accounts.forEach { QuotaAccountCard(it, snapshot.freshnessMs, stale, layout) }
        }
    }
    Text("适用于额度、余额和支出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun QuotaLayoutChoice(option: QuotaLayout, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val compact = option == QuotaLayout.COMPACT
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) colors.primaryContainer else colors.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.size(width = 88.dp, height = 68.dp)
                    .background(colors.surface, RoundedCornerShape(8.dp)).padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(if (compact) 4 else 3) {
                    Box(Modifier.fillMaxWidth().weight(1f)
                        .background(if (selected) colors.secondaryContainer else colors.surfaceVariant, RoundedCornerShape(3.dp)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RadioButton(selected, onClick = null)
                Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}

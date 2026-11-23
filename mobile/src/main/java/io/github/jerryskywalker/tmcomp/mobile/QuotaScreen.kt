package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.Reading
import io.github.jerryskywalker.tmcomp.core.Snapshot
import io.github.jerryskywalker.tmcomp.core.displayClock
import io.github.jerryskywalker.tmcomp.core.displayNumber
import io.github.jerryskywalker.tmcomp.core.locallyStale
import io.github.jerryskywalker.tmcomp.core.value

internal const val QUOTA_LAYOUT_KEY = "quota_layout_v1"
internal const val QUOTA_ORDER_KEY = "quota_order_v1"

internal enum class QuotaLayout(val label: String) {
    STANDARD("标准"),
    COMPACT("紧凑"),
}

@Composable
internal fun QuotaScreen(snapshot: Snapshot?, stale: Boolean, kind: String, layout: QuotaLayout, order: List<String>,
    subscriptions: SubscriptionState?, onAccount: (String) -> Unit, onKind: (String) -> Unit) {
    val accounts = snapshot.quotaOrder(kind, order)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val tabs = listOf("quota" to "额度", "balance" to "余额", "spend" to "支出", "subscriptions" to "订阅")
        tabs.forEachIndexed { index, (key, label) ->
            SegmentedButton(kind == key, { onKind(key) }, SegmentedButtonDefaults.itemShape(index, tabs.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary),
                icon = {},
            ) { Text(label) }
        }
    }
    if (kind == "subscriptions") {
        SubscriptionsSection(subscriptions, layout == QuotaLayout.COMPACT)
        return
    }
    if (snapshot == null) {
        Text("等待首份额度快照")
        return
    }
    if (accounts.isEmpty()) Text(when (kind) {
        "balance" -> "Hub 暂未上报钱包余额"
        "spend" -> "Hub 暂未上报按量支出"
        else -> "Hub 暂未上报额度"
    })
    accounts.forEach { account -> QuotaAccountCard(account, snapshot.freshnessMs, stale, layout) { onAccount(account.sortId) } }
}

internal fun Snapshot?.quotaOrder(kind: String, order: List<String>): List<QuotaAccount> {
    if (this == null) return emptyList()
    val index = order.withIndex().associate { it.value to it.index }
    return quotaAccounts(readings, toolSources).mapNotNull { it.forKind(kind) }
        .sortedBy { index[it.sortId] ?: Int.MAX_VALUE }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuotaAccountCard(account: QuotaAccount, freshnessMs: Long, stale: Boolean,
    layout: QuotaLayout = QuotaLayout.STANDARD,
    onOpen: (() -> Unit)? = null,
) {
    val dense = layout == QuotaLayout.COMPACT
    val (mark, color) = when (account.providerId) {
        "codex" -> "CX" to Color(0xff086e68)
        "antigravity" -> "AG" to Color(0xff477527)
        "opencode" -> "OC" to Color(0xff9e4074)
        "cursor" -> "CU" to Color(0xff695590)
        "copilot", "github-copilot" -> "GH" to Color(0xff785912)
        else -> account.provider.take(2).uppercase() to MaterialTheme.colorScheme.primary
    }
    val source = account.source?.source ?: account.readings.map { it.source }.distinct().singleOrNull()
    val observed = (account.readings.mapNotNull { it.observedAt } + listOfNotNull(account.source?.observedAt))
        .maxOrNull()
    val sections = quotaSections(account)
    val typography = MaterialTheme.typography
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val plan = account.account?.takeIf { it.isNotBlank() }
    val metadata = listOfNotNull(
        source?.let(::sourceLabel),
        observed?.let { "观测 " + displayClock(it) },
        "缓存".takeIf { stale || (account.readings.isNotEmpty() && account.readings.all {
            it.stale || locallyStale(it.observedAt, freshnessMs)
        }) },
    ).joinToString(" · ")
    OutlinedCard(
        Modifier.fillMaxWidth().then(if (onOpen == null) Modifier else Modifier.clickable(onClickLabel = "查看账户额度明细", onClick = onOpen)),
        shape = RoundedCornerShape(if (dense) 14.dp else 20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val gap = if (dense) 4.dp else 8.dp
        Column(Modifier.padding(if (dense) 8.dp else 13.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (dense) 6.dp else 9.dp)) {
                Box(Modifier.size(if (dense) 24.dp else 36.dp)
                    .background(color.copy(alpha = .12f), RoundedCornerShape(if (dense) 7.dp else 12.dp)),
                    contentAlignment = Alignment.Center) {
                    Text(mark, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                if (dense) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(account.provider, Modifier.align(Alignment.CenterVertically),
                            style = typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        plan?.let {
                            Text(it, Modifier.align(Alignment.CenterVertically), style = typography.labelMedium, color = secondary)
                        }
                        if (metadata.isNotEmpty()) Text(metadata, Modifier.align(Alignment.CenterVertically),
                            style = typography.labelSmall, color = secondary)
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(account.provider, style = typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        plan?.let { Text(it, style = typography.labelMedium, color = secondary) }
                    }
                }
                if (onOpen != null) Text("›", style = typography.titleMedium, color = secondary)
            }

            if (sections.isEmpty()) {
                val message = when (account.source?.status) {
                    "unauthorized" -> "额度需要授权"
                    "disabled" -> "额度采集已停用"
                    else -> "额度暂未上报"
                }
                Text(message, Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(if (dense) 5.dp else 10.dp),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                BoxWithConstraints {
                    val canPair = maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.25f
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        sections.forEach { section ->
                            section.title?.let {
                                Text(it, style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val rows = if (dense && canPair && section.rows.all { it.size == 1 }) {
                                section.rows.flatten().chunked(2)
                            } else section.rows
                            rows.forEach { row ->
                                if (canPair && row.size == 2) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(if (dense) 4.dp else 7.dp)) {
                                        row.forEach {
                                            QuotaWindow(it, color, freshnessMs, stale, source,
                                                Modifier.weight(1f), dense, paired = true)
                                        }
                                    }
                                } else {
                                    row.forEach {
                                        QuotaWindow(it, color, freshnessMs, stale, source, Modifier.fillMaxWidth(),
                                            dense, inlineMetadata = dense && canPair)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!dense && metadata.isNotEmpty()) Text(metadata, style = typography.labelSmall, color = secondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuotaWindow(tile: QuotaTile, color: Color, freshnessMs: Long, stale: Boolean,
    accountSource: String?, modifier: Modifier, dense: Boolean, paired: Boolean = false, inlineMetadata: Boolean = false,
) {
    val row = tile.reading
    val cached = stale || row.stale || locallyStale(row.observedAt, freshnessMs)
    val percent = row.remainingPercent?.takeIf { row.kind == "quota" && !row.unlimited }
    val amount = when {
        row.unlimited -> "不限量"
        percent != null -> displayNumber(percent) + "%"
        row.kind != "quota" && row.amount != null -> listOfNotNull(displayNumber(row.amount), row.currency).joinToString(" ")
        else -> row.value()
    }
    val caption = when {
        percent != null -> "剩余额度"
        row.kind == "balance" && row.amount != null && !row.unlimited -> "可用余额"
        row.kind == "spend" && row.amount != null && !row.unlimited -> "已花费"
        else -> null
    }
    val tint = if (cached) MaterialTheme.colorScheme.onSurfaceVariant else color
    val metadata = listOfNotNull(
        row.boundaryAt?.let {
            val action = when (row.boundaryKind) {
                "expiry" -> "到期 "
                "mixed" -> "重置／到期 "
                else -> "重置 "
            }
            action + displayClock(it)
        },
        row.source?.takeUnless { it == accountSource }?.let(::sourceLabel),
        "缓存".takeIf { cached },
    ).joinToString(" · ")
    val typography = MaterialTheme.typography
    val labelStyle = if (dense) typography.labelMedium else typography.labelLarge
    val amountStyle = when {
        dense -> typography.titleSmall
        paired -> typography.titleLarge
        else -> typography.headlineSmall
    }
    val inline = (dense && LocalDensity.current.fontScale <= 1.25f) || (paired && percent != null)
    Column(modifier.background(tint.copy(alpha = .075f), RoundedCornerShape(if (dense) 8.dp else 13.dp))
        .padding(horizontal = if (dense) 6.dp else 10.dp, vertical = if (dense) 3.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (dense) 1.dp else 3.dp)) {
        if (inline) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (inlineMetadata && metadata.isNotEmpty()) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tile.title, Modifier.align(Alignment.CenterVertically),
                            style = labelStyle, fontWeight = FontWeight.SemiBold)
                        Text(metadata, Modifier.align(Alignment.CenterVertically),
                            style = typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(tile.title, Modifier.weight(1f), style = labelStyle, fontWeight = FontWeight.SemiBold)
                }
                val amountModifier = if (caption == null) Modifier else Modifier.semantics {
                    contentDescription = "$caption $amount"
                }
                Text(amount, amountModifier, color = tint, fontWeight = FontWeight.Bold, style = amountStyle)
            }
        } else {
            Text(tile.title, style = labelStyle, fontWeight = FontWeight.SemiBold)
            Text(amount, color = tint, fontWeight = FontWeight.Bold, style = amountStyle)
            caption?.let { Text(it, style = typography.labelSmall) }
        }
        if (percent != null) LinearProgressIndicator(
            progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = if (dense) 1.dp else 3.dp),
            color = if (!cached && percent <= 10) MaterialTheme.colorScheme.error else tint,
            trackColor = tint.copy(alpha = .15f),
        )
        if (!inlineMetadata && metadata.isNotEmpty()) Text(metadata,
            style = typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sourceLabel(source: String) = when (source.lowercase()) {
    "oauth" -> "OAuth"
    "api" -> "API"
    "web" -> "网页"
    "local" -> "本机"
    "rpc" -> "本机 RPC"
    else -> source
}

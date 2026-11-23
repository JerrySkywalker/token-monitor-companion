package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.displayNumber

@Composable
internal fun SubscriptionsSection(state: SubscriptionState?, compact: Boolean) {
    val records = state?.data?.subscriptions
    if (records == null) {
        Text(when {
            state?.unavailable == true -> "当前 Hub 未提供订阅接口。"
            state?.stale == true -> "订阅暂时无法读取。"
            else -> "等待 Hub 订阅。"
        }, style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (state.stale) Text("正在显示上次同步的订阅", style = MaterialTheme.typography.labelMedium)
    if (records.isEmpty()) Text("Hub 还没有订阅记录。", style = MaterialTheme.typography.bodyMedium)
    records.forEach { record ->
        val provider = when (record.provider) {
            "codex" -> "Codex / ChatGPT"
            "copilot", "github-copilot" -> "GitHub Copilot"
            else -> record.provider
        }
        val plan = record.planName?.takeIf(String::isNotBlank) ?: if (record.kind == "topup") "充值账户" else "订阅套餐"
        val cycleUnit = when (record.interval) {
            "day" -> "天"
            "week" -> "周"
            "month" -> "个月"
            "year" -> "年"
            else -> null
        }
        val cycle = cycleUnit?.let { "每 ${record.intervalCount} $it" } ?: "—"
        val renewal = record.nextRenewal()?.toString() ?: if (!record.autoRenew) "不自动续费" else "—"
        val currency = record.currency.orEmpty()
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(if (compact) 9.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
                Text("$provider · $plan", style = MaterialTheme.typography.titleMedium)
                record.binding?.profileName?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium)
                }
                if (record.kind == "topup") {
                    val latest = record.topUps.maxByOrNull { it.date }
                    LedgerLine("最近充值", latest?.let { "$currency ${displayNumber(it.amountMinor / 100.0)} · ${it.date}" } ?: "—")
                    LedgerLine("累计充值", "$currency ${displayNumber(record.topUps.sumOf { it.amountMinor } / 100.0)}")
                } else {
                    LedgerLine("订阅金额", record.amountMinor?.let { "$currency ${displayNumber(it / 100.0)}" } ?: "—")
                    LedgerLine("续费周期", cycle)
                    LedgerLine("下次续费", renewal)
                }
                record.endDate?.takeIf(String::isNotBlank)?.let { LedgerLine("到期日", it) }
            }
        }
    }
}

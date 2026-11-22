package io.github.jerryskywalker.tmcomp.core

enum class WatchQuotaCategory(val label: String) { SHORT("短时"), CYCLE("周期"), BALANCE("余额") }

data class WatchQuotaGroup(val provider: String, val id: String, val accounts: List<WatchQuotaAccount>)
data class WatchQuotaAccount(val label: String?, val windows: List<Reading>)

val Reading.watchQuotaCategory: WatchQuotaCategory? get() = when {
    kind == "balance" -> WatchQuotaCategory.BALANCE
    kind != "quota" -> null
    shortWindow || windowKind in setOf("daily", "day") || windowMinutes == 1440L -> WatchQuotaCategory.SHORT
    else -> WatchQuotaCategory.CYCLE
}

private data class QuotaWindowKey(val label: String, val kind: String?, val minutes: Long?, val currency: String?)

fun watchQuotaGroups(rows: List<Reading>, category: WatchQuotaCategory): List<WatchQuotaGroup> {
    val matching = rows.filter { it.watchQuotaCategory == category }
        .sortedWith(compareBy<Reading>({ it.stale }, { it.remainingPercent ?: 101.0 }))

    return matching.groupBy { it.toolKey.lowercase() }.map { (providerId, readings) ->
        val accounts = readings.groupBy { it.accountId ?: it.account }.values.map { windows ->
            val recentFirst = windows.sortedWith(compareBy<Reading> { it.stale }.thenByDescending { it.observedAt })
            val distinct = recentFirst.distinctBy {
                QuotaWindowKey(it.label.trim().lowercase(), it.windowKind, it.windowMinutes, it.currency)
            }.sortedBy { it.windowMinutes ?: Long.MAX_VALUE }
            WatchQuotaAccount(distinct.first().account, distinct)
        }
        WatchQuotaGroup(readings.first().provider, providerId, accounts)
    }
}

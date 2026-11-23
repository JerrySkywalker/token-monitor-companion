package io.github.jerryskywalker.tmcomp.core

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class HubStats(
    val periods: HubPeriods,
    val updatedAt: JsonElement? = null,
    val staleAfterMs: JsonElement? = null,
    val limits: HubLimits? = null,
    val historyPreview: HubHistory? = null,
    val devices: List<HubDevice> = emptyList(),
    val subscriptionsUpdatedAt: String? = null,
)

@Serializable
internal data class HubPeriods(
    val today: HubPeriod? = null,
    val month: HubPeriod? = null,
    val week: HubPeriod? = null,
    val allTime: HubPeriod? = null,
)

@Serializable
internal data class HubPeriod(
    val totalTokens: JsonElement? = null,
    val costUsd: JsonElement? = null,
    val unpricedTokens: JsonElement? = null,
    val cacheReadTokens: JsonElement? = null,
    val cacheWriteTokens: JsonElement? = null,
    val outputTokens: JsonElement? = null,
    val unclassifiedTokens: JsonElement? = null,
    val capabilities: JsonObject? = null,
    val clients: JsonObject? = null,
    val clientCosts: JsonObject? = null,
    val models: JsonObject? = null,
    val modelCosts: JsonObject? = null,
    val clientModels: JsonObject? = null,
    val clientModelCosts: JsonObject? = null,
)

@Serializable
internal data class HubDevice(
    val deviceId: JsonElement? = null,
    val hostname: JsonElement? = null,
    val receivedAt: JsonElement? = null,
    val updatedAt: JsonElement? = null,
    val periods: HubPeriods? = null,
)

@Serializable
internal data class HubLimits(val updatedAt: JsonElement? = null, val providers: List<JsonObject?> = emptyList())

@Serializable
internal data class HubHistory(val daily: List<JsonObject?> = emptyList(), val dailyTotals: List<JsonObject?> = emptyList())

private val providerLabels = mapOf(
    "codex" to "Codex", "claude" to "Claude", "cursor" to "Cursor", "gemini" to "Gemini",
    "opencode" to "OpenCode", "antigravity" to "Antigravity", "copilot" to "GitHub Copilot",
    "github-copilot" to "GitHub Copilot", "openrouter" to "OpenRouter", "deepseek" to "DeepSeek",
)

private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }
    ?.doubleOrNull?.takeIf(Double::isFinite)
private fun JsonElement?.count(): Long? = number()?.takeIf {
    it >= 0 && it <= 9_007_199_254_740_991.0 && it == it.toLong().toDouble()
}?.toLong()
private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.label(): String? = string()?.trim()?.take(70)?.takeIf(String::isNotEmpty)
private fun JsonElement?.flag() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
private fun JsonElement?.instant(): Instant? = string()?.let { runCatching { Instant.parse(it) }.getOrNull() }
private fun JsonElement?.orElse(next: JsonElement?) = if (this == null || this == JsonNull) next else this
private fun stableId(vararg values: JsonElement): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(JsonArray(values.toList()).toString().encodeToByteArray())).take(22)
private fun isStale(at: Instant?, now: Instant, freshness: Long) = at == null || at.isBefore(now.minusMillis(freshness))

private fun breakdown(tokens: JsonObject?, costs: JsonObject?, max: Int) = tokens.orEmpty().entries
    .mapNotNull { (key, value) ->
        val count = value.count() ?: return@mapNotNull null
        if (key.isEmpty()) return@mapNotNull null
        UsageEntry(key.take(100), (providerLabels[key] ?: key).take(100), count, costs?.get(key).number())
    }.sortedByDescending { it.tokens }.take(max)

private fun parts(tokens: Long?, cacheRead: Long?, cacheWrite: Long?, output: Long?, unclassified: Long?): Long? {
    if (tokens == null || cacheRead == null || cacheWrite == null || output == null || unclassified == null) return null
    return (tokens - cacheRead - cacheWrite - output - unclassified).takeIf { it >= 0L }
}

private fun HubPeriod?.toPeriod(detailed: Boolean = true): Period {
    if (this == null) return Period()
    val tokens = totalTokens.count()
    val read = cacheReadTokens.count()
    val write = cacheWriteTokens.count()
    val out = outputTokens.count()
    val unknown = unclassifiedTokens.count() ?: 0L.takeIf { capabilities?.get("tokenComponents").flag() }
    return Period(tokens = tokens, costUsd = costUsd.number(), unpricedTokens = unpricedTokens.count(),
        input = parts(tokens, read, write, out, unknown), cacheRead = read, cacheWrite = write, output = out, unclassified = unknown,
        tools = breakdown(clients, clientCosts, if (detailed) 80 else 30),
        models = if (detailed) breakdown(models, modelCosts, 80) else emptyList(),
        toolModels = clientModels.orEmpty().entries.take(if (detailed) 80 else 12).associate { (key, models) ->
            key.take(100) to breakdown(models as? JsonObject, clientModelCosts?.get(key) as? JsonObject, if (detailed) 20 else 6)
        })
}

private fun historyRows(history: List<JsonObject?>, previous: List<TrendDay>, today: LocalDate,
    days: Int): Pair<List<TrendDay>, Map<LocalDate, JsonObject>> {
    val observed = history.mapNotNull { row ->
        val date = row?.get("date").string()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (row != null && date != null && date in today.minusDays(days.toLong())..today.plusDays(1)) date to row else null
    }.toMap()
    val old = previous.filter { it.tokens != null || it.costUsd != null }.mapNotNull { row ->
        runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row }
    }.toMap()
    if (observed.isEmpty() && old.isEmpty()) return emptyList<TrendDay>() to observed
    val latest = maxOf(today, observed.keys.maxOrNull() ?: today)
    val rows = (days - 1 downTo 0).map { offset ->
        val date = latest.minusDays(offset.toLong())
        val row = observed[date]
        if (row != null) TrendDay(date.toString(), row["tokens"].orElse(row["totalTokens"]).count(),
            row["cost"].orElse(row["costUsd"]).number(),
            (row["perClient"] as? JsonObject)?.entries?.take(80)?.mapNotNull { (key, value) ->
                val tool = value as? JsonObject
                val count = tool?.get("tokens").orElse(tool?.get("totalTokens")).orElse(value).count()
                count?.let { UsageEntry(key.take(100), (providerLabels[key] ?: key).take(100), it,
                    tool?.get("cost").orElse(tool?.get("costUsd")).number()) }
            } ?: old[date]?.tools.orEmpty())
        else old[date] ?: TrendDay(date.toString())
    }
    return rows to observed
}

private fun calendarWeek(rows: List<TrendDay>, originals: Map<LocalDate, JsonObject>): Period? {
    val latest = rows.lastOrNull()?.let { LocalDate.parse(it.date) } ?: return null
    val days = rows.takeLast(latest.dayOfWeek.value)
    if (days.size != latest.dayOfWeek.value || days.any { it.tokens == null }) return null
    val totals = days.map { originals[LocalDate.parse(it.date)] }
    fun summed(key: String) = totals.map { it?.get(key).count() }.let { values ->
        if (values.any { it == null }) null else values.filterNotNull().sum()
    }
    val tokens = days.sumOf { requireNotNull(it.tokens) }
    val costs = days.map { it.costUsd }
    val read = summed("cacheReadTokens")
    val write = summed("cacheWriteTokens")
    val out = summed("outputTokens")
    val classified = totals.map { row ->
        row?.get("unclassifiedTokens").count() ?: 0L.takeIf { row?.get("tokenComponentsAvailable").flag() }
    }.let { if (it.any { amount -> amount == null }) null else it.filterNotNull().sum() }
    val perClient = totals.map { it?.get("perClient") as? JsonObject }
    val tools = if (perClient.all { it != null }) perClient.filterNotNull().flatMap { it.entries }
        .groupBy({ it.key }, { it.value }).mapNotNull { (key, values) ->
            val entries = values.mapNotNull { value ->
                val row = value as? JsonObject
                val count = row?.get("tokens").orElse(row?.get("totalTokens")).orElse(value).count()
                count?.let { it to row?.get("cost").orElse(row?.get("costUsd")).number() }
            }
            if (entries.isEmpty()) null else UsageEntry(key.take(100), (providerLabels[key] ?: key).take(100),
                entries.sumOf { it.first }, entries.map { it.second }.let {
                    if (it.any { cost -> cost == null }) null else it.filterNotNull().sum()
                })
        }.sortedByDescending { it.tokens }.take(80) else emptyList()
    return Period(tokens, if (costs.any { it == null }) null else costs.filterNotNull().sum(),
        summed("unpricedTokens"), parts(tokens, read, write, out, classified), read, write, out, classified, tools)
}

internal fun projectHub(stats: HubStats, fallback: HubHistory?, now: Instant, previous: List<TrendDay> = emptyList(),
    historyDays: Int = 30): Snapshot {
    val upstream = stats.updatedAt.instant()
    val freshness = (stats.staleAfterMs.number() ?: 1_800_000.0).coerceIn(60_000.0, 604_800_000.0).toLong()
    val readings = mutableListOf<Reading>()
    val sources = mutableListOf<ToolSource>()
    val duplicates = mutableMapOf<List<JsonElement>, Int>()
    for (account in stats.limits?.providers.orEmpty().take(120).filterNotNull()) {
        val provider = account["provider"].label() ?: "Unknown provider"
        val providerId = if (provider == "github-copilot") "copilot" else provider
        val label = account["providerLabel"].label() ?: providerLabels[provider] ?: provider
        val plan = account["accountLabel"].orElse(account["accountName"]).label()
        val key = account["accountKey"].orElse(account["accountId"]).orElse(account["id"]).orElse(JsonPrimitive(plan.orEmpty()))
            ?: JsonPrimitive("")
        val identity = stableId(JsonPrimitive(provider), key)
        val observed = account["updatedAt"].instant() ?: stats.limits?.updatedAt.instant()
        val source = account["source"].label()
        val status = account["status"].label()
        sources += ToolSource(providerId, label, identity, status ?: "unknown", source, observed?.toString())
        val windows = (account["windows"] as? JsonArray).orEmpty().take(24).filterIsInstance<JsonObject>().toMutableList()
        val balance = account["balance"] as? JsonObject
        val legacy = account["balanceUsd"].number()
        if ((balance != null || legacy != null) && windows.none { it["metric"].string() == "credits" }) {
            windows += buildJsonObject {
                put("kind", "balance")
                put("metric", "credits")
                put("label", "Balance")
                put("boundaryKind", "expiry")
                put("remaining", balance?.get("amount").orElse(legacy?.let(::JsonPrimitive)) ?: JsonNull)
                put("resetsAt", balance?.get("expiresAt") ?: JsonNull)
                put("currency", balance?.get("currency").orElse("USD".takeIf { legacy != null }?.let(::JsonPrimitive)) ?: JsonNull)
                put("unlimited", balance?.get("unlimited") ?: JsonNull)
            }
        }
        for (window in windows) {
            val windowKind = window["kind"].label()
            val name = window["label"].label() ?: windowKind ?: "Quota"
            val kind = when (window["metric"].string()) { "credits" -> "balance"; "spend" -> "spend"; else -> "quota" }
            val limitId = window["limitId"].takeUnless { it == JsonNull }
            val shape = listOf(JsonPrimitive(provider), key, JsonPrimitive(kind),
                limitId ?: window["kind"].takeUnless { it == JsonNull } ?: JsonPrimitive(name),
                if (limitId == null) JsonPrimitive(name) else JsonNull)
            val index = duplicates.getOrDefault(shape, 0)
            duplicates[shape] = index + 1
            val remaining = window["remainingPercent"].number() ?: window["usedPercent"].number()?.let { 100.0 - it }
            val boundary = window["resetsAt"].orElse(window["expiresAt"]).instant()
            val boundaryKind = window["boundaryKind"].string()?.takeIf { it in setOf("expiry", "mixed") } ?: "reset"
            readings += Reading(id = stableId(*(shape + JsonPrimitive(index)).toTypedArray()), provider = label, account = plan,
                label = name, kind = kind, remainingPercent = remaining?.takeIf { kind == "quota" }?.coerceIn(0.0, 100.0),
                amount = window[if (kind == "spend") "used" else "remaining"].number(), limit = window["limit"].number(),
                currency = window["currency"].label(), boundaryAt = boundary?.toString(), boundaryKind = boundaryKind,
                observedAt = observed?.toString(), stale = account["stale"].flag() || (status != null && status != "ok") ||
                    isStale(observed, now, freshness) || (boundary != null && !boundary.isAfter(now)),
                unlimited = window["unlimited"].flag(), providerId = providerId, accountId = identity,
                source = window["source"].label() ?: source, windowKind = windowKind, windowMinutes = window["windowMinutes"].count())
        }
    }
    val inputs = fallback?.dailyTotals.orEmpty().takeLast(400) +
        fallback?.daily.orEmpty().takeLast(400) + stats.historyPreview?.daily.orEmpty().takeLast(400)
    val (history, originals) = historyRows(inputs, previous, now.atZone(ZoneOffset.UTC).toLocalDate(), historyDays)
    val collectors = stats.devices.take(20).mapIndexedNotNull { index, device ->
        val id = device.deviceId.string()?.takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
        val at = device.receivedAt.instant() ?: device.updatedAt.instant()
        Collector(stableId(JsonPrimitive("device"), JsonPrimitive(id)), device.hostname.label() ?: "设备 ${index + 1}",
            device.periods?.today.toPeriod(false), device.periods?.month.toPeriod(false),
            device.periods?.allTime?.toPeriod(false), at?.toString(), isStale(at, now, freshness))
    }
    return Snapshot(1, freshness, now.toString(), upstream?.toString(), isStale(upstream, now, freshness),
        stats.periods.today.toPeriod(), stats.periods.month.toPeriod(), readings, stats.periods.allTime?.toPeriod(),
        stats.periods.week?.toPeriod() ?: calendarWeek(history, originals), history, collectors, sources,
        subscriptionsUpdatedAt = stats.subscriptionsUpdatedAt)
}

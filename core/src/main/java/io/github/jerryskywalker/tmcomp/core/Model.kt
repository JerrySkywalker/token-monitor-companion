package io.github.jerryskywalker.tmcomp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Period(
    val tokens: Long? = null,
    val costUsd: Double? = null,
    val unpricedTokens: Long? = null,
    val input: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val output: Long? = null,
    val unclassified: Long? = null,
    val tools: List<UsageEntry> = emptyList(),
    val models: List<UsageEntry> = emptyList(),
    val toolModels: Map<String, List<UsageEntry>> = emptyMap(),
)

@Serializable
data class UsageEntry(val key: String, val label: String, val tokens: Long, val costUsd: Double? = null)

@Serializable
data class TrendDay(val date: String, val tokens: Long? = null, val costUsd: Double? = null)

@Serializable
data class Collector(
    val key: String,
    val label: String,
    val today: Period,
    val month: Period,
    val allTime: Period? = null,
    val updatedAt: String? = null,
    val stale: Boolean = false,
)

@Serializable
data class Reading(
    val id: String,
    val provider: String,
    val account: String? = null,
    val label: String,
    val kind: String,
    val remainingPercent: Double? = null,
    val amount: Double? = null,
    val limit: Double? = null,
    val currency: String? = null,
    val boundaryAt: String? = null,
    val boundaryKind: String = "reset",
    val observedAt: String? = null,
    val stale: Boolean = false,
    val unlimited: Boolean = false,
    val providerId: String? = null,
    val accountId: String? = null,
    val source: String? = null,
    val windowKind: String? = null,
    val windowMinutes: Long? = null,
) {
    val toolKey: String get() = providerId ?: provider
    val shortWindow: Boolean get() = windowKind == "session" || (windowMinutes ?: Long.MAX_VALUE) < 1440
}

@Serializable
data class ToolSource(
    val id: String, val label: String, val accountId: String, val status: String,
    val source: String? = null, val observedAt: String? = null,
)

@Serializable
data class Snapshot(
    val schema: Int,
    val freshnessMs: Long = 1_800_000,
    val retrievedAt: String,
    val upstreamAt: String? = null,
    val stale: Boolean,
    val today: Period,
    val month: Period,
    val readings: List<Reading>,
    val allTime: Period? = null,
    val week: Period? = null,
    val history: List<TrendDay> = emptyList(),
    val collectors: List<Collector> = emptyList(),
    val toolSources: List<ToolSource> = emptyList(),
) {
    fun forWatch(pin: String?): Snapshot {
        val sorted = readings.sortedWith(compareBy({ it.stale }, { it.remainingPercent ?: 101.0 }))
        fun summary(period: Period) = period.copy(tools = period.tools.take(12), models = period.models.take(8), toolModels = emptyMap())
        return copy(
            today = summary(today), month = summary(month), week = null, allTime = null,
            history = history.takeLast(30), toolSources = emptyList(),
            collectors = collectors.take(12).map { it.copy(today = Period(it.today.tokens), month = Period(it.month.tokens), allTime = null) },
            readings = (listOfNotNull(readings.find { it.id == pin }) +
                sorted.filter { it.kind == "quota" }.take(64) +
                sorted.filter { it.kind == "balance" }.take(12)).distinctBy { it.id },
        )
    }
}

@Serializable
enum class Mode { PHONE, DIRECT }

@Serializable
enum class WatchStatus { READY, PAUSED, NEEDS_PAIRING, WAITING, OFFLINE }

@Serializable
data class Report(
    val appliedVersion: Int,
    val sequence: Long,
    val mode: Mode,
    val state: WatchStatus,
    val snapshotAt: String? = null,
    val hubOrigin: String? = null,
    val readings: List<Reading> = emptyList(),
    val ringProvider: String? = null,
)

@Serializable
data class WatchConfig(
    val id: String,
    val label: String,
    val version: Int = 0,
    val mode: Mode = Mode.PHONE,
    val pinnedId: String? = null,
    val paused: Boolean = false,
    val refreshMinutes: Long = 30,
    val refreshRequest: Int = 0,
    val report: Report? = null,
    val ringProvider: String? = null,
)

@Serializable
data class PhonePacket(
    val binding: String,
    val origin: String,
    val config: WatchConfig,
    val snapshot: Snapshot? = null,
    val detached: Boolean = false,
)

@Serializable
data class WatchPacket(
    val id: String,
    val label: String,
    val binding: String? = null,
    val report: Report? = null,
    val initial: WatchConfig? = null,
)

@Serializable
data class WatchHubSetup(val id: String, val binding: String, val hub: HubConnection)

@Serializable
data class WatchHubResult(val origin: String? = null, val error: String? = null)

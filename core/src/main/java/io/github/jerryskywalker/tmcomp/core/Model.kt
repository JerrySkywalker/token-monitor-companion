package io.github.jerryskywalker.tmcomp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Period(val tokens: Long? = null, val costUsd: Double? = null, val unpricedTokens: Long? = null)

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
) {
    fun forWatch(pin: String?): Snapshot {
        val sorted = readings.sortedWith(compareBy({ it.stale }, { it.remainingPercent ?: 101.0 }))
        return copy(readings = listOfNotNull(
            readings.find { it.id == pin },
            sorted.find { it.kind == "quota" },
            sorted.find { it.kind == "balance" },
        ).distinctBy { it.id })
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
) {
    fun edit(from: WatchConfig) = ConfigEdit(from.version, mode, pinnedId, paused, refreshMinutes, refreshRequest)
}

@Serializable
data class ConfigEdit(
    val expectedVersion: Int,
    val mode: Mode,
    val pinnedId: String?,
    val paused: Boolean,
    val refreshMinutes: Long,
    val refreshRequest: Int,
)

@Serializable
data class Registration(val label: String)

@Serializable
data class Removed(val removed: Boolean)

@Serializable
data class Enrollment(val code: String, val kind: String, val label: String, val installationId: String? = null)

@Serializable
data class EnrollmentResult(val id: String, val token: String, val kind: String, val watchId: String? = null)

@Serializable
data class PairCode(val url: String, val code: String, val expires: Long = 0)

@Serializable
data class Credentials(val origin: String, val token: String)

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
)

@Serializable
data class DirectInvite(val id: String, val binding: String, val pair: PairCode)

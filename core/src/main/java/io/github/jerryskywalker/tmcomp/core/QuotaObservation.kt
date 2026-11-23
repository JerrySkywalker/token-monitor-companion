package io.github.jerryskywalker.tmcomp.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

data class QuotaObservation(
    val at: Long,
    val day: String,
    val value: Double,
    val unit: String,
    val allowance: Double? = null,
    val resetAt: Long? = null,
    val increasesWithUsage: Boolean = false,
) {
    fun changeSince(previous: QuotaObservation): Double? {
        if (at <= previous.at || unit != previous.unit || increasesWithUsage != previous.increasesWithUsage ||
            allowance != null && previous.allowance != null && allowance != previous.allowance) return null
        val beforeReset = previous.resetAt
        val afterReset = resetAt
        if (beforeReset != null && afterReset != null &&
            (abs(beforeReset - afterReset) > 1000 || at >= minOf(beforeReset, afterReset))) return null
        if ((beforeReset == null) != (afterReset == null)) return null
        val delta = if (increasesWithUsage) value - previous.value else previous.value - value
        return when {
            delta >= 0 -> delta
            unit == "%" && delta > -1 -> 0.0
            else -> null
        }
    }
}

fun Reading.observeQuota(now: Instant, zone: ZoneId = ZoneId.systemDefault()): QuotaObservation? {
    if (stale || unlimited) return null
    val observed = observedAt.asInstant() ?: return null
    if (observed > now.plusSeconds(60)) return null
    val reset = boundaryAt.asInstant()?.takeIf { boundaryKind == "reset" }
    if (reset != null && observed >= reset) return null
    val percent = remainingPercent?.takeIf { kind == "quota" } ?: if (kind == "quota") {
        limit?.takeIf { it > 0 }?.let { allowance -> amount?.let { it / allowance * 100 } }
    } else null
    val value = percent?.coerceIn(0.0, 100.0) ?: amount ?: return null
    if (!value.isFinite() || value < 0) return null
    return QuotaObservation(observed.toEpochMilli(), observed.atZone(zone).toLocalDate().toString(), value,
        if (percent != null) "%" else currency.orEmpty(), limit, reset?.toEpochMilli(), kind == "spend")
}

private fun String?.asInstant() = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

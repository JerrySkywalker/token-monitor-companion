package io.github.jerryskywalker.tmcomp.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

@Serializable
data class HubSubscriptions(val ok: Boolean, val subscriptions: List<HubSubscription>, val updatedAt: String? = null)

@Serializable
data class HubSubscription(
    val id: String,
    val provider: String,
    val kind: String = "subscription",
    val binding: SubscriptionBinding? = null,
    val planName: String? = null,
    val amountMinor: Long? = null,
    val currency: String? = null,
    val interval: String? = null,
    val intervalCount: Int = 1,
    val startDate: String? = null,
    val autoRenew: Boolean = true,
    val nextRenewalOverride: String? = null,
    val endDate: String? = null,
    val topUps: List<SubscriptionTopUp> = emptyList(),
) {
    fun nextRenewal(today: LocalDate = LocalDate.now()): LocalDate? {
        if (!autoRenew || kind == "topup") return null
        val end = endDate.asDate()
        if (end != null && end <= today) return null
        nextRenewalOverride.asDate()?.takeIf { it >= today }?.let { return it.takeIf { end == null || it < end } }
        val start = startDate.asDate() ?: return null
        val step = intervalCount.toLong().takeIf { it in 1..1200 } ?: return null
        val unit = when (interval) {
            "day" -> ChronoUnit.DAYS
            "week" -> ChronoUnit.WEEKS
            "month" -> ChronoUnit.MONTHS
            "year" -> ChronoUnit.YEARS
            else -> return null
        }
        val elapsed = (unit.between(start, today) / step).coerceAtLeast(0)
        val candidate = start.plus(step * elapsed, unit)
        val next = if (candidate < today) start.plus(step * (elapsed + 1), unit) else candidate
        return next.takeIf { end == null || it < end }
    }
}

@Serializable
data class SubscriptionBinding(val profileName: String? = null)

@Serializable
data class SubscriptionTopUp(val date: String, val amountMinor: Long)

private fun String?.asDate() = this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

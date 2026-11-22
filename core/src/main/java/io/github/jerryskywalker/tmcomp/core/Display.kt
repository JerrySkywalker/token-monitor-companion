package io.github.jerryskywalker.tmcomp.core

import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun displayNumber(value: Number?) = value?.let { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(it) } ?: "—"

fun displayCompactTokens(value: Long?): String = when {
    value == null -> "—"
    value >= 1_000_000_000 -> displayNumber(value / 1_000_000_000.0) + "B"
    value >= 1_000_000 -> displayNumber(value / 1_000_000.0) + "M"
    value >= 1_000 -> displayNumber(value / 1_000.0) + "K"
    else -> displayNumber(value)
}

fun Reading.title() = listOfNotNull(provider, account, label).joinToString(" · ")

fun Reading.value() = when {
    unlimited -> "不限量"
    kind == "quota" -> remainingPercent?.let { "剩余 " + displayNumber(it) + "%" } ?: amount?.let { "剩余 " + displayNumber(it) + (limit?.let { cap -> " / " + displayNumber(cap) }.orEmpty()) } ?: "额度未知"
    amount == null -> if (kind == "spend") "花费未知" else "余额未知"
    else -> listOfNotNull(if (kind == "spend") "已花费" else "余额", displayNumber(amount), currency).joinToString(" ")
}

fun displayClock(timestamp: String?) = timestamp?.let {
    runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
} ?: "未知时间"

fun locallyStale(timestamp: String?, freshnessMs: Long): Boolean = runCatching {
    Duration.between(Instant.parse(timestamp), Instant.now()).toMillis() > freshnessMs
}.getOrDefault(true)

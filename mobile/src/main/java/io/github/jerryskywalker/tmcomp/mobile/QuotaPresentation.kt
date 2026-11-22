package io.github.jerryskywalker.tmcomp.mobile

import io.github.jerryskywalker.tmcomp.core.Reading
import io.github.jerryskywalker.tmcomp.core.ToolSource

internal data class QuotaAccount(
    val providerId: String,
    val provider: String,
    val account: String?,
    val readings: List<Reading>,
    val source: ToolSource?,
)

private data class AccountKey(val provider: String, val account: String?)

internal fun quotaAccounts(readings: List<Reading>, sources: List<ToolSource>): List<QuotaAccount> {
    val windows = readings.groupBy { AccountKey(it.toolKey.lowercase(), it.accountId ?: it.account) }
    val status = sources.associateBy { AccountKey(it.id.lowercase(), it.accountId) }
    return (windows.keys + status.keys).map { key ->
        val rows = windows[key].orEmpty()
        val source = status[key]
        QuotaAccount(key.provider, rows.firstOrNull()?.provider ?: requireNotNull(source).label,
            rows.firstOrNull()?.account, rows, source)
    }
}

internal fun QuotaAccount.forKind(kind: String): QuotaAccount? {
    val selected = readings.filter { it.kind == kind }
    if (selected.isEmpty() && !(kind == "quota" && readings.isEmpty() && source != null)) return null
    return copy(readings = if (kind == "quota") selected + readings.filter { it.kind == "balance" } else selected)
}

internal data class QuotaTile(val reading: Reading, val title: String)
internal data class QuotaSection(val title: String?, val rows: List<List<QuotaTile>>)

private val antigravityPeriods = Regex("""\s+(5-hour|weekly)$""", RegexOption.IGNORE_CASE)

internal fun quotaSections(account: QuotaAccount): List<QuotaSection> {
    val (windows, money) = account.readings.partition { it.kind == "quota" }
    val sections = if (account.providerId == "antigravity") antigravitySections(windows)
    else if (windows.isEmpty()) emptyList()
    else listOf(QuotaSection(
        if (account.providerId == "opencode" && windows.any { it.isMonthlyWindow() }) "Go 额度" else null,
        windowRows(account.providerId, windows.map { QuotaTile(it, windowTitle(it)) }),
    ))
    return sections + money.map { reading ->
        val name = if (account.providerId == "opencode" && reading.kind == "balance" &&
            reading.label.lowercase() in setOf("balance", "credits", "zen balance")) "Zen 余额" else reading.label
        QuotaSection(null, listOf(listOf(QuotaTile(reading, name))))
    }
}

private fun antigravitySections(windows: List<Reading>): List<QuotaSection> {
    val groups = linkedMapOf<String, MutableList<QuotaTile>>()
    val other = mutableListOf<QuotaTile>()
    for (reading in windows) {
        val label = reading.label.trim()
        val match = antigravityPeriods.find(label)
        val group = match?.let { label.substring(0, it.range.first).trim().trimEnd('·', ':', ' ').trim() }
        if (match == null || group.isNullOrBlank()) {
            other.add(QuotaTile(reading, windowTitle(reading)))
        } else {
            val title = if (match.groupValues[1].equals("weekly", true)) "每周" else "5 小时"
            groups.getOrPut(group, ::mutableListOf).add(QuotaTile(reading, title))
        }
    }
    val priority = listOf("Gemini", "Claude/GPT", "Gemini 4 Argon")
    val grouped = groups.entries.sortedBy { priority.indexOf(it.key).takeIf { index -> index >= 0 } ?: priority.size }
        .map { (title, tiles) -> QuotaSection(title, windowRows("antigravity", tiles)) }
    return if (other.isEmpty()) grouped else grouped + QuotaSection(null, other.map(::listOf))
}

private fun windowRows(providerId: String, tiles: List<QuotaTile>): List<List<QuotaTile>> {
    if (providerId in setOf("cursor", "copilot", "github-copilot")) return tiles.map(::listOf)
    val short = tiles.firstOrNull { shortTile(providerId, it) }
    val week = tiles.firstOrNull { weeklyTile(providerId, it) }
    if (short == null || week == null) return tiles.map(::listOf)
    return listOf(listOf(short, week)) + tiles.filter { it !== short && it !== week }.map(::listOf)
}

private fun shortTile(providerId: String, tile: QuotaTile): Boolean {
    val label = tile.reading.label.trim().lowercase()
    if (providerId == "antigravity") return tile.title == "5 小时"
    return label in setOf("session", "5-hour", "5h", "5 hour", "daily", "day", "24h") ||
        (providerId == "opencode" && tile.reading.windowKind in setOf("session", "daily"))
}

private fun weeklyTile(providerId: String, tile: QuotaTile): Boolean {
    if (providerId == "antigravity") return tile.title == "每周"
    return tile.reading.label.trim().lowercase() in setOf("weekly", "week", "7-day", "7d") ||
        (providerId == "opencode" && tile.reading.windowKind == "weekly")
}

private fun Reading.isMonthlyWindow() = windowMinutes?.let { it in 40_320L..44_640L } == true

private fun windowTitle(reading: Reading) = when (reading.label.trim().lowercase()) {
    "session", "5-hour", "5h", "5 hour" ->
        if (reading.windowMinutes == 300L || reading.label.trim().lowercase() in setOf("5-hour", "5h", "5 hour")) "5 小时" else "会话窗口"
    "weekly", "week", "7-day", "7d" -> "每周"
    "daily", "day", "24h" -> "每日"
    "monthly", "month" -> "每月"
    "billing" -> if (reading.isMonthlyWindow()) "每月" else "账期"
    else -> reading.label
}

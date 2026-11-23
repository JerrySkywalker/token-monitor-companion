package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.tmcomp.core.QuotaObservation
import io.github.jerryskywalker.tmcomp.core.Reading
import io.github.jerryskywalker.tmcomp.core.displayClock
import io.github.jerryskywalker.tmcomp.core.displayNumber
import io.github.jerryskywalker.tmcomp.core.value
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

@Composable
internal fun QuotaDetail(account: QuotaAccount?, scope: String?, fetchedAt: String?) {
    if (account == null) {
        Text("当前快照没有该账户。")
        return
    }
    Text(account.provider, style = MaterialTheme.typography.headlineSmall)
    account.account?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    var selected by rememberSaveable(account.sortId) { mutableStateOf(account.readings.firstOrNull()?.id) }
    if (account.readings.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        account.readings.forEach { row -> FilterChip(selected == row.id, { selected = row.id }, { Text(row.label) }) }
    }
    val reading = account.readings.firstOrNull { it.id == selected } ?: account.readings.firstOrNull()
    if (reading == null) {
        Text("该账户暂未上报可用额度。")
        return
    }
    LedgerLine(reading.label, reading.value())
    reading.boundaryAt?.let { LedgerLine(if (reading.boundaryKind == "expiry") "到期时间" else "重置时间", displayClock(it)) }
    if (scope == null) {
        Text("等待首次额度观测。")
        return
    }
    key(reading.id, scope) { QuotaDetailHistory(reading, scope, fetchedAt) }
}

@Composable
private fun QuotaDetailHistory(reading: Reading, scope: String, fetchedAt: String?) {
    val context = LocalContext.current.applicationContext
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    var failed by remember { mutableStateOf(false) }
    val history by produceState<QuotaHistoryView?>(null, fetchedAt, today) {
        try {
            value = QuotaHistory(context).use { it.view(scope, reading.id, today) }
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    if (failed) {
        Text("本机额度历史暂时无法读取。")
        return
    }
    val data = history ?: return
    val points = data.points
    val unit = points.lastOrNull()?.unit.orEmpty()
    val deadline = reading.boundaryAt?.takeIf { reading.boundaryKind == "reset" }
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val duration = reading.windowMinutes?.takeIf { it in 1..63_360 } ?: when (reading.windowKind) {
        "daily" -> 1440L
        "weekly" -> 10_080L
        else -> null
    }
    val cycleStart = if (deadline != null && duration != null) deadline - duration * 60_000 else null
    val now = Instant.now().toEpochMilli()
    val start = cycleStart ?: today.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = deadline.takeIf { cycleStart != null } ?: now
    val visible = points.filter { it.at in start..end && it.unit == unit && (cycleStart == null ||
        it.resetAt?.let { observed -> kotlin.math.abs(observed - requireNotNull(deadline)) <= 1000 } == true) }
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (reading.kind == "spend") "累计支出走势" else "剩余走势", style = MaterialTheme.typography.titleMedium)
            Text(if (cycleStart != null) "当前额度周期" else "最近 14 天", style = MaterialTheme.typography.labelMedium)
            if (visible.isEmpty()) Text("等待额度观测", style = MaterialTheme.typography.bodySmall)
            else QuotaTrend(visible, start, end, unit, cycleStart != null)
        }
    }
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("每日额度用量 · 14 天", style = MaterialTheme.typography.titleMedium)
            QuotaDaily(today, data.days, unit)
            Text("按本机收到的同日有效观测计算。空白表示没有可比较的读数。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuotaTrend(points: List<QuotaObservation>, start: Long, end: Long, unit: String, cycle: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val maximum = if (unit == "%") 100.0 else points.maxOf { it.value }.coerceAtLeast(1.0)
    var selected by remember(points) { mutableStateOf<QuotaObservation?>(null) }
    Canvas(Modifier.fillMaxWidth().height(112.dp).semantics {
        contentDescription = "额度走势 · ${points.size} 个观测" + if (unit == "%" && cycle) " · 虚线为匀速消耗参考" else ""
    }.pointerInput(points, start, end) {
        detectTapGestures { touch ->
            val instant = start + (end - start) * (touch.x / size.width).coerceIn(0f, 1f).toDouble()
            selected = points.minByOrNull { kotlin.math.abs(it.at - instant) }
        }
    }) {
        fun position(point: QuotaObservation) = Offset(size.width * ((point.at - start).toDouble() / (end - start).coerceAtLeast(1)).toFloat(),
            size.height * (1 - point.value / maximum).toFloat())
        drawLine(track, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1.dp.toPx())
        if (cycle && unit == "%") drawLine(track, Offset.Zero, Offset(size.width, size.height), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        points.zipWithNext().forEach { (before, after) ->
            if (after.changeSince(before) != null) drawLine(accent, position(before), position(after), strokeWidth = 2.dp.toPx())
        }
        points.forEach { drawCircle(accent, if (it == selected) 4.dp.toPx() else 1.5.dp.toPx(), position(it)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(displayClock(Instant.ofEpochMilli(start).toString()), style = MaterialTheme.typography.labelSmall)
        Text(displayClock(Instant.ofEpochMilli(end).toString()), style = MaterialTheme.typography.labelSmall)
    }
    val point = selected ?: points.last()
    LedgerLine("观测 · ${displayClock(Instant.ofEpochMilli(point.at).toString())}", "${displayNumber(point.value)}$unit")
}

@Composable
private fun QuotaDaily(today: LocalDate, observed: Map<String, Double>, unit: String) {
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    var selected by remember(today) { mutableIntStateOf(days.lastIndex) }
    val peak = observed.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(64.dp).semantics {
        contentDescription = "最近 14 天每日额度用量 · ${days.count { it.toString() in observed }} 天有观测"
    }.pointerInput(days) {
        detectTapGestures { selected = (it.x / (size.width / days.size)).toInt().coerceIn(days.indices) }
    }) {
        val slot = size.width / days.size
        drawLine(track, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx())
        days.forEachIndexed { index, day ->
            observed[day.toString()]?.let { amount ->
                val height = (size.height * amount / peak).toFloat().coerceAtLeast(1.dp.toPx())
                drawRect(accent.copy(alpha = if (index == selected) 1f else .52f), Offset(slot * (index + .18f), size.height - height),
                    Size(slot * .64f, height))
            }
        }
    }
    val day = days[selected]
    val value = observed[day.toString()]
    LedgerLine(if (day == today) "今日观测用量" else day.toString(), value?.let { "${displayNumber(it)}$unit" } ?: "观测不足")
}

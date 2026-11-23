package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.jerryskywalker.tmcomp.core.Range

internal data class ReorderEntry(val id: String, val title: String, val subtitle: String? = null)

internal fun <T> List<T>.move(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }

@Composable
internal fun ReorderRows(entries: List<ReorderEntry>, move: (Int, Int) -> Unit, remove: ((String) -> Unit)? = null) {
    val latest by rememberUpdatedState(entries)
    val onMove by rememberUpdatedState(move)
    val rowHeight = 58.dp
    val gap = 5.dp
    val stride = with(LocalDensity.current) { (rowHeight + gap).toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        entries.forEachIndexed { index, entry ->
            key(entry.id) {
                val active = dragging == entry.id
                Surface(Modifier.fillMaxWidth().height(rowHeight).zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active) offset else 0f }
                    .semantics {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction("上移") { onMove(index, index - 1); true }.takeIf { index > 0 },
                            CustomAccessibilityAction("下移") { onMove(index, index + 1); true }.takeIf { index < entries.lastIndex },
                        )
                    }, shape = MaterialTheme.shapes.medium,
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shadowElevation = if (active) 5.dp else 0.dp) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(50.dp).fillMaxHeight().semantics { contentDescription = "拖动${entry.title}" }
                            .pointerInput(entry.id, stride) {
                                var target = 0
                                fun finish() { dragging = null; offset = 0f }
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        target = latest.indexOfFirst { it.id == entry.id }
                                        dragging = entry.id
                                        offset = 0f
                                    },
                                    onDragEnd = ::finish,
                                    onDragCancel = ::finish,
                                ) { _, delta ->
                                    if (target < 0) return@detectVerticalDragGestures
                                    val previous = target
                                    var distance = offset + delta
                                    while (distance > stride / 2 && target < latest.lastIndex) { target++; distance -= stride }
                                    while (distance < -stride / 2 && target > 0) { target--; distance += stride }
                                    if (target != previous) onMove(previous, target)
                                    offset = distance.coerceIn(if (target == 0) -stride / 3 else -stride,
                                        if (target == latest.lastIndex) stride / 3 else stride)
                                }
                            }, contentAlignment = Alignment.Center) {
                            Text("⠿", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                            entry.subtitle?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                        }
                        if (remove != null) TextButton({ remove(entry.id) },
                            modifier = Modifier.semantics { contentDescription = "移除${entry.title}" }) { Text("移除") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditOverview(current: List<OverviewCard>, onSave: (List<OverviewCard>) -> Unit, onCancel: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(ArrayList(current)) }
    SettingsHeading("展示队列")
    if (draft.isEmpty()) Text("从下方添加卡片。")
    ReorderRows(draft.map { ReorderEntry(it.name, it.title) },
        { from, to -> draft = ArrayList(draft.move(from, to)) },
        { id -> draft = ArrayList(draft.filterNot { it.name == id }) })
    val available = OverviewCard.entries - draft.toSet()
    if (available.isNotEmpty()) {
        SettingsHeading("可添加卡片")
        SettingsGroup {
            available.forEach { card ->
                ListItem(headlineContent = { Text(card.title) },
                    trailingContent = { TextButton({ draft = ArrayList(draft + card) }) { Text("添加") } })
            }
        }
    }
    TextButton({ draft = ArrayList(OverviewCard.entries) }) { Text("恢复默认") }
    LayoutActions(onCancel) { onSave(draft) }
}

@Composable
internal fun EditOverviewRanges(current: List<Range>, onSave: (List<Range>) -> Unit, onCancel: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(ArrayList(current)) }
    Text("最多固定 4 个常用时间范围。其他范围可从总览的「更多时间」临时选择。")
    SettingsHeading("已固定 ${draft.size} / 4")
    ReorderRows(draft.map { ReorderEntry(it.name, it.label) },
        { from, to -> draft = ArrayList(draft.move(from, to)) },
        if (draft.size > 1) { name -> draft = ArrayList(draft.filterNot { it.name == name }) } else null)
    SettingsHeading("可添加时间")
    SettingsGroup {
        rangeChoices.filterNot(draft::contains).forEach { range ->
            ListItem(headlineContent = { Text(range.label) },
                trailingContent = { TextButton({ draft = ArrayList(draft + range) }, enabled = draft.size < 4) { Text("添加") } })
        }
    }
    LayoutActions(onCancel) { onSave(draft) }
}

@Composable
internal fun EditQuotaOrder(accounts: List<QuotaAccount>, onSave: (List<String>) -> Unit, onCancel: () -> Unit) {
    val byId = remember { accounts.associateBy { it.sortId } }
    var saved by rememberSaveable { mutableStateOf(ArrayList(byId.keys)) }
    val draft = saved.filter(byId::containsKey)
    Text("拖动手柄调整当前分类中的账户卡片顺序。")
    ReorderRows(draft.mapNotNull { key -> byId[key]?.let { ReorderEntry(key, it.provider, it.account) } },
        { from, to -> saved = ArrayList(draft.move(from, to)) })
    LayoutActions(onCancel) { onSave(draft) }
}

@Composable
private fun LayoutActions(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onCancel) { Text("取消") }
        Button(onSave) { Text("保存布局") }
    }
}

package io.github.jerryskywalker.tmcomp.wear

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import io.github.jerryskywalker.tmcomp.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchScreen() } }
    }
}

@Composable
private fun WatchScreen() {
    val context = LocalContext.current
    val sync = remember { WatchSync(context) }
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf(sync.view()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.localizedMessage ?: "操作失败"
            } finally {
                busy = false
                view = sync.view()
            }
        }
    }

    LaunchedEffect(Unit) {
        action { sync.tick() }
        while (true) {
            delay(1_500)
            if (!busy) view = sync.view()
        }
    }

    val config = view.config
    val report = view.report
    val snapshot = view.snapshot
    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 32.dp),
            autoCentering = null,
        ) {
            item { Text("Token Monitor", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
            item {
                val label = when (config?.mode) {
                    null -> "等待绑定"
                    Mode.PHONE -> "手机同步"
                    Mode.DIRECT -> "独立直连"
                }
                Text(label + if (config != null) " · v" + config.version else "", color = MaterialTheme.colors.primary)
            }
            item {
                val description = when {
                    busy -> "更新中"
                    config?.paused == true -> "已暂停"
                    report?.state == WatchStatus.NEEDS_PAIRING -> "等待直连授权"
                    report?.state == WatchStatus.OFFLINE -> "服务器暂不可达"
                    snapshot == null && config == null -> "请在手机应用中绑定手表，或手动连接服务器。"
                    snapshot == null -> "等待快照"
                    snapshot.stale || locallyStale(snapshot.upstreamAt, snapshot.freshnessMs) -> "可能过期 · " + displayClock(snapshot.upstreamAt)
                    else -> "更新于 " + displayClock(snapshot.upstreamAt)
                }
                Text(description, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1)
            }
            (error ?: view.error)?.let { failure -> item { Text(failure, textAlign = TextAlign.Center, color = Color(0xffffb4ab)) } }
            if (snapshot != null) {
                val readings = snapshot.readings.let { rows ->
                    val pinned = rows.find { it.id == config?.pinnedId }
                    listOfNotNull(pinned) + rows.filterNot { it == pinned }
                }
                for (reading in readings) {
                    item {
                        Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            Text(reading.title(), style = MaterialTheme.typography.caption2)
                            Text(reading.value(), style = MaterialTheme.typography.title3)
                            if (reading.boundaryAt != null) {
                                val label = when (reading.boundaryKind) { "expiry" -> "到期 "; "mixed" -> "重置 / 到期 "; else -> "重置 " }
                                Text(label + displayClock(reading.boundaryAt), style = MaterialTheme.typography.caption2)
                            }
                            if (reading.stale || locallyStale(reading.observedAt, snapshot.freshnessMs) || snapshot.stale) {
                                Text("数据可能过期", style = MaterialTheme.typography.caption2)
                            }
                        }
                    }
                }
                item {
                    Text("今日 " + displayNumber(snapshot.today.tokens) + " tokens", style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
                }
            }
            item {
                Chip(onClick = { action { sync.tick(true) } }, label = { Text("刷新") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            if (config == null || config.mode == Mode.DIRECT) {
                item {
                    Chip(onClick = {
                        prompt(context, "服务器地址", sync.savedOrigin(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { url ->
                            prompt(context, "一次性配对码", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) { code ->
                                action { sync.enroll(PairCode(origin(url, BuildConfig.DEBUG), pairingCode(code))) }
                            }
                        }
                    }, label = { Text("输入直连配对码") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
            }
            if (config != null) {
                item {
                    Chip(onClick = {
                        AlertDialog.Builder(context).setTitle("清除手表绑定？").setMessage("要撤销服务器授权，请同时在手机中移除此手表。")
                            .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> action { sync.reset() } }.show()
                    }, label = { Text("清除绑定") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun prompt(context: android.content.Context, title: String, old: String, type: Int, value: (String) -> Unit) {
    val field = EditText(context).apply { inputType = type; setSingleLine(); setText(old); setSelection(text.length) }
    val dialog = AlertDialog.Builder(context).setTitle(title).setView(field)
        .setNegativeButton("取消", null).setPositiveButton("继续") { _, _ -> value(field.text.toString()) }.create()
    field.requestFocus()
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
}

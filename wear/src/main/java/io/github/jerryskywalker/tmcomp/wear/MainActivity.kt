package io.github.jerryskywalker.tmcomp.wear

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import io.github.jerryskywalker.tmcomp.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    var connection by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

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
    val ring = config?.ringProvider
    val report = view.report
    val snapshot = view.snapshot
    BackHandler(connection && snapshot != null) { connection = false }
    if (!connection && snapshot != null) {
        val status = when {
            busy -> "更新中"
            config?.paused == true -> "已暂停"
            report?.state == WatchStatus.OFFLINE || error != null -> "更新失败"
            config?.mode == Mode.DIRECT -> "独立直连"
            else -> "手机推送"
        }
        StopWatchDashboard(snapshot, config, status, ring, { connection = true }) { action { sync.tick(true) } }
        return
    }
    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 32.dp),
            autoCentering = null,
        ) {
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Image(painterResource(io.github.jerryskywalker.tmcomp.core.R.drawable.tmcomp_logo), null, Modifier.size(19.dp))
                    Text("Token Monitor", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
                }
            }
            item {
                val label = when (config?.mode) {
                    null -> "等待绑定"
                    Mode.PHONE -> "手机推送"
                    Mode.DIRECT -> "独立直连"
                }
                Text(label + if (config != null) " · v" + config.version else "", color = MaterialTheme.colors.primary)
            }
            item {
                val description = when {
                    busy -> "更新中"
                    config?.paused == true -> "已暂停"
                    report?.state == WatchStatus.NEEDS_PAIRING -> "等待 Hub 地址和 Secret"
                    report?.state == WatchStatus.OFFLINE -> "Hub 暂不可达"
                    snapshot == null && config == null -> "在手机的 Wear OS 设置中添加手表。"
                    snapshot == null -> "等待快照"
                    snapshot.stale || locallyStale(snapshot.upstreamAt, snapshot.freshnessMs) -> "可能过期 · " + displayClock(snapshot.upstreamAt)
                    else -> "更新于 " + displayClock(snapshot.upstreamAt)
                }
                Text(description, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1)
            }
            (error ?: view.error)?.let { failure -> item { Text(failure, textAlign = TextAlign.Center, color = Color(0xffffb4ab)) } }
            if (snapshot != null) {
                item {
                    val source = if (config?.mode == Mode.DIRECT) "本机取数" else "手机取数"
                    Text(source + " " + localFetchClock(snapshot.retrievedAt),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1)
                }
                item { Chip(onClick = { connection = false }, label = { Text("返回看板") }, modifier = Modifier.fillMaxWidth()) }
            }
            item {
                Chip(onClick = { importing = true }, label = { Text("从手机导入 Hub") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            if (config == null || config.mode == Mode.DIRECT) {
                item {
                    Chip(onClick = {
                        promptHubInput(context, "Hub 地址", sync.hubOrigin(), InputType.TYPE_TEXT_VARIATION_URI) { address ->
                            promptHubSecret(context) { secret ->
                                action {
                                    sync.configureLocalHub(hubConnection(address, secret, BuildConfig.DEBUG))
                                    connection = false
                                }
                            }
                        }
                    }, label = { Text("本机配置 Hub") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
            }
            if (view.localOnly) {
                item {
                    Chip(onClick = { action { sync.enablePhoneDiscovery() } }, label = { Text("允许手机发现") },
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
            }
            if (snapshot != null) {
                item { Text("外圈来源", color = MaterialTheme.colors.primary) }
                item { Chip(onClick = { action { sync.changeRing(null) } },
                    label = { Text("跟随置顶" + if (ring == null) " ✓" else "") }, modifier = Modifier.fillMaxWidth()) }
                item { Chip(onClick = { action { sync.changeRing("") } },
                    label = { Text("关闭外圈" + if (ring == "") " ✓" else "") }, modifier = Modifier.fillMaxWidth()) }
                snapshot.readings.filter { it.kind == "quota" }.distinctBy { it.toolKey }.forEach { row ->
                    item {
                        Chip(onClick = { action { sync.changeRing(row.toolKey) } },
                            label = { Text(row.provider + if (ring == row.toolKey) " ✓" else "") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Chip(onClick = { action { sync.tick(true) } }, label = { Text("刷新") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            report?.hubOrigin?.let { address ->
                item { Text(address, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2) }
            }
            if (config != null) {
                item {
                    Chip(onClick = {
                        AlertDialog.Builder(context).setTitle("清除手表配置？").setMessage("本机会清除 Hub 配置、快照和手机绑定。")
                            .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> action { sync.reset() } }.show()
                    }, label = { Text("清除配置") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (importing) {
        WatchImportHub(sync, onDismiss = { importing = false }) {
            importing = false
            view = sync.view()
            connection = false
            error = null
        }
    }
}

private fun localFetchClock(timestamp: String) = runCatching {
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(timestamp))
}.getOrDefault("未知")

// 自托管 Hub 使用 API 密钥，手表在 Data Layer 不可用时仍需要一个独立的输入入口。
@SuppressLint("WearPasswordInput")
private fun promptHubSecret(context: Context, onSecret: (String) -> Unit) =
    promptHubInput(context, "Hub Secret", "", InputType.TYPE_TEXT_VARIATION_PASSWORD, secure = true, onValue = onSecret)

private fun promptHubInput(
    context: Context,
    title: String,
    current: String,
    variation: Int,
    secure: Boolean = false,
    onValue: (String) -> Unit,
) {
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        setSingleLine(true)
        if (secure) isSaveEnabled = false
        setText(current)
        setSelection(text.length)
    }
    val dialog = AlertDialog.Builder(context).setTitle(title).setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton(if (secure) "连接 Hub" else "继续") { _, _ -> onValue(input.text.toString()) }
        .create()
    if (secure) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    input.requestFocus()
    dialog.show()
}

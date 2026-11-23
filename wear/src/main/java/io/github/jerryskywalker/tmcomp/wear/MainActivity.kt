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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    var settingsPage by rememberSaveable { mutableStateOf(WatchSettingsPage.HOME) }
    var importing by remember { mutableStateOf<Boolean?>(null) }
    var hub by remember { mutableStateOf<HubConnection?>(null) }
    var phoneConnected by remember { mutableStateOf<Boolean?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

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

    val config = view.config
    val ring = config?.ringProvider
    val report = view.report
    val snapshot = view.snapshot
    LaunchedEffect(sync) { sync.changes().collect { view = it } }
    LaunchedEffect(sync) { sync.hubChanges().collect { hub = it } }
    val usePhoneTransport = !view.localOnly
    LaunchedEffect(lifecycle, usePhoneTransport) {
        if (!usePhoneTransport) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            sync.receivePhoneUpdate()
            sync.phoneUpdates().collect { sync.receivePhoneUpdate() }
        }
    }
    val direct = config?.mode == Mode.DIRECT && !config.paused
    LaunchedEffect(lifecycle, direct, hub?.origin, hub?.secret) {
        val credentials = hub ?: return@LaunchedEffect
        if (!direct) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Hub(credentials).refreshWhileVisible(300_000) { sync.tick() }
        }
    }
    val showPhoneStatus = usePhoneTransport && (config?.mode == Mode.PHONE || connection)
    LaunchedEffect(lifecycle, showPhoneStatus) {
        if (!showPhoneStatus) {
            phoneConnected = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                phoneConnected = sync.phoneConnected()
                delay(4_000)
            }
        }
    }
    fun backFromSettings() {
        if (settingsPage == WatchSettingsPage.RING) settingsPage = WatchSettingsPage.DISPLAY
        else if (settingsPage != WatchSettingsPage.HOME) settingsPage = WatchSettingsPage.HOME
        else if (snapshot != null) connection = false
    }
    BackHandler(importing == null && (settingsPage != WatchSettingsPage.HOME || connection && snapshot != null)) {
        backFromSettings()
    }
    if (!connection && snapshot != null) {
        val status = when {
            busy -> "更新中"
            config?.paused == true -> "已暂停"
            report?.state == WatchStatus.OFFLINE || error != null -> "更新失败"
            config?.mode == Mode.DIRECT -> "独立直连"
            phoneConnected == false -> "等待手机连接"
            else -> "手机推送"
        }
        StopWatchDashboard(snapshot, config, status, ring, { connection = true }) { action { sync.tick(true) } }
        return
    }
    val listState = key(settingsPage) { rememberScalingLazyListState() }
    val backdrop = if (importing == null) Modifier else Modifier.blur(8.dp)
    Scaffold(modifier = backdrop, timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 32.dp),
            autoCentering = null,
        ) {
            item { Text(settingsPage.label, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
            if (settingsPage != WatchSettingsPage.HOME) {
                item { CompactChip(onClick = ::backFromSettings, label = { Text("返回设置") }) }
            }
            when (settingsPage) {
                WatchSettingsPage.HOME -> {
                    item { Text(watchConnectionLabel(config, phoneConnected), style = MaterialTheme.typography.caption1) }
                    item { WatchMenuItem("连接与同步") { settingsPage = WatchSettingsPage.CONNECTION } }
                    if (config != null) {
                        item { WatchMenuItem("显示设置") { settingsPage = WatchSettingsPage.DISPLAY } }
                        item { WatchMenuItem("管理") { settingsPage = WatchSettingsPage.MANAGE } }
                    }
                    if (snapshot != null) item { WatchMenuItem("返回看板", onClick = ::backFromSettings) }
                }
                WatchSettingsPage.CONNECTION -> {
                    item { Text(watchConnectionLabel(config, phoneConnected), color = MaterialTheme.colors.primary) }
                    val status = when {
                        busy -> "更新中"
                        config?.paused == true -> "已暂停"
                        report?.state == WatchStatus.NEEDS_PAIRING -> "等待 Hub 设置"
                        error != null || report?.state == WatchStatus.OFFLINE -> "Hub 暂不可达"
                        snapshot == null -> "等待用量快照"
                        snapshot.stale || locallyStale(snapshot.upstreamAt, snapshot.freshnessMs) -> "正在显示缓存"
                        else -> "数据更新于 " + displayClock(snapshot.upstreamAt)
                    }
                    item { Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1) }
                    (error ?: view.error)?.let { failure -> item { Text(failure, textAlign = TextAlign.Center, color = Color(0xffffb4ab)) } }
                    if (snapshot != null) item {
                        val source = if (config?.mode == Mode.DIRECT) "本机取数" else "手机取数"
                        Text("$source ${localFetchClock(snapshot.retrievedAt)}", style = MaterialTheme.typography.caption1)
                    }
                    if (config != null) item { WatchMenuItem("刷新", !busy) { action { sync.tick(true) } } }
                    item { WatchMenuItem("从手机导入 Hub", !busy) { importing = false } }
                    if (config == null || config.mode == Mode.DIRECT) item {
                        WatchMenuItem("本机配置 Hub", !busy) {
                            promptHubInput(context, "Hub 地址", sync.hubOrigin(), InputType.TYPE_TEXT_VARIATION_URI) { address ->
                                promptHubSecret(context) { secret ->
                                    action {
                                        sync.configureLocalHub(hubConnection(address, secret, BuildConfig.DEBUG))
                                        settingsPage = WatchSettingsPage.HOME
                                        connection = false
                                    }
                                }
                            }
                        }
                    }
                    if (view.localOnly) item {
                        WatchMenuItem("允许手机发现", !busy) { action { sync.enablePhoneDiscovery() } }
                    }
                    report?.hubOrigin?.let { address ->
                        item { Text(address, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2) }
                    }
                }
                WatchSettingsPage.DISPLAY -> {
                    if (config != null && (view.localOnly || sync.link() == null)) item {
                        WatchMenuItem("从手机导入显示设置", !busy) { importing = true }
                    }
                    if (snapshot != null) item { WatchMenuItem("外圈来源") { settingsPage = WatchSettingsPage.RING } }
                    if (config != null && !view.localOnly && sync.link() != null) item {
                        Text("在手机的 Wear OS 设置中调整图表和配色。", textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.caption1)
                    }
                }
                WatchSettingsPage.RING -> {
                    item { WatchMenuItem("跟随置顶" + if (ring == null) " ✓" else "", !busy) { action { sync.changeRing(null) } } }
                    item { WatchMenuItem("关闭外圈" + if (ring == "") " ✓" else "", !busy) { action { sync.changeRing("") } } }
                    snapshot?.readings.orEmpty().filter { it.kind == "quota" }.distinctBy { it.toolKey }.forEach { row ->
                        item { WatchMenuItem(row.provider + if (ring == row.toolKey) " ✓" else "", !busy) { action { sync.changeRing(row.toolKey) } } }
                    }
                }
                WatchSettingsPage.MANAGE -> if (config != null) item {
                    WatchMenuItem("清除配置", !busy) {
                        AlertDialog.Builder(context).setTitle("清除手表配置？").setMessage("本机会清除 Hub 配置、快照和手机绑定。")
                            .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                                action {
                                    sync.reset()
                                    settingsPage = WatchSettingsPage.HOME
                                }
                            }.show()
                    }
                }
            }
        }
    }
    importing?.let { displayOnly ->
        WatchImportHub(sync, displayOnly, onDismiss = { importing = null }) {
            importing = null
            view = sync.view()
            connection = false
            settingsPage = WatchSettingsPage.HOME
            error = null
        }
    }
}

private enum class WatchSettingsPage(val label: String) {
    HOME("设置"), CONNECTION("连接与同步"), DISPLAY("显示设置"), RING("外圈来源"), MANAGE("管理"),
}

private fun watchConnectionLabel(config: WatchConfig?, phoneConnected: Boolean?): String = when (config?.mode) {
    null -> "等待配置 Hub 或绑定手机"
    Mode.DIRECT -> "独立直连"
    Mode.PHONE -> when (phoneConnected) {
        true -> "手机推送 · 已连接"
        false -> "手机推送 · 等待连接"
        null -> "手机推送"
    }
}

@Composable
private fun WatchMenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Chip(onClick = onClick, label = { Text(label) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
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

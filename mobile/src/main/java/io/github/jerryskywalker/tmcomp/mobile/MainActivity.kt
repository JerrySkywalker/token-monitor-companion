package io.github.jerryskywalker.tmcomp.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.jerryskywalker.tmcomp.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PhoneScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneScreen() {
    val context = LocalContext.current
    val sync = remember { PhoneSync(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(sync.state()) }
    var credentials by remember { mutableStateOf(sync.storage.credentials()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var directCode by remember { mutableStateOf<PairCode?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<WatchConfig?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun act(block: suspend () -> Unit) {
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
                credentials = sync.storage.credentials()
                state = sync.state()
                busy = false
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value -> act { sync.enroll(scanPairing(value, BuildConfig.DEBUG)) } }
    }
    LaunchedEffect(credentials?.origin) {
        if (credentials == null) return@LaunchedEffect
        PhoneSync.schedule(context)
        if (!busy) act { sync.refresh() }
        var previous = 0
        while (true) {
            delay(1_000)
            if (!busy) state = sync.state()
            previous++
            if (previous >= 20) {
                previous = 0
                if (!busy) act { sync.refresh() }
            }
        }
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("Token Monitor", style = MaterialTheme.typography.headlineLarge)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (credentials == null) {
                Text("连接到自托管的 Companion Server")
                Button(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描 Companion Server 配对码").setBeepEnabled(false)) }, enabled = !busy) {
                    Text("扫描配对二维码")
                }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("服务器地址") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("一次性配对码") }, singleLine = true)
                Button(onClick = { act { sync.enroll(PairCode(origin(url, BuildConfig.DEBUG), pairingCode(code))); code = "" } },
                    enabled = !busy && url.isNotBlank() && code.isNotBlank()) { Text("配对服务器") }
            } else {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("用量") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("手表") })
                    Tab(tab == 2, { tab = 2 }, text = { Text("设置") })
                }
                if (tab == 0) {
                    OutlinedButton(onClick = { act { sync.refresh() } }, enabled = !busy) { Text("刷新用量") }
                    state.usageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val snapshot = state.snapshot
                    if (snapshot == null) Text("等待首份用量数据") else Usage(snapshot, state.usageError != null)
                }
                if (tab == 1) {
                    OutlinedButton(onClick = { act { sync.refresh() } }, enabled = !busy) { Text("查找手表") }
                    if (state.available.isEmpty() && state.watches.isEmpty()) Text("在已配对的 Wear OS 手表上打开 Token Monitor。")
                    for (watch in state.available) {
                        if (state.watches.none { it.id == watch.id } || watch.id !in state.connected) {
                            OutlinedButton(onClick = { act { sync.bind(watch) } }, enabled = !busy) { Text("绑定 " + watch.label) }
                        }
                    }
                    for (watch in state.watches) {
                        WatchSettings(watch, watch.id in state.connected, sync.link(watch.id) != null, state.snapshot?.readings.orEmpty(), busy,
                            onChange = { next -> act { directCode = sync.edit(watch, next) } },
                            onCode = { act { directCode = sync.invite(watch) } },
                            onRemove = { confirmRemove = watch })
                    }
                }
                if (tab == 2) {
                    Text("服务器")
                    Text(credentials?.origin.orEmpty())
                    OutlinedButton(onClick = { confirmClear = true }, enabled = !busy) { Text("清除本机连接") }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    directCode?.let { pair ->
        AlertDialog(onDismissRequest = { directCode = null }, title = { Text("手表直连配对") },
            text = { Text("已尝试将配对信息发送到手表。若手表提示需要授权，在手表上输入服务器地址和一次性码。\n\n" + pair.url + "\n" + pair.code + "\n有效期 5 分钟。") },
            confirmButton = { TextButton(onClick = { directCode = null }) { Text("完成") } })
    }
    confirmRemove?.let { watch ->
        AlertDialog(onDismissRequest = { confirmRemove = null }, title = { Text("移除 " + watch.label) },
            text = { Text("服务器将撤销该手表的直连权限。") }, dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消") }
            }, confirmButton = {
                TextButton(onClick = { confirmRemove = null; act { sync.remove(watch) } }) { Text("移除") }
            })
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除手机上的服务器连接？") },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            confirmButton = { TextButton(onClick = { confirmClear = false; act { sync.clear() } }) { Text("清除") } })
    }
}

@Composable
private fun Usage(snapshot: Snapshot, unavailable: Boolean) {
    val stale = unavailable || snapshot.stale || locallyStale(snapshot.upstreamAt, snapshot.freshnessMs)
    Text((if (stale) "数据显示可能过期 · " else "数据时间 · ") + displayClock(snapshot.upstreamAt))
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今日 " + displayNumber(snapshot.today.tokens) + " tokens · USD " + displayNumber(snapshot.today.costUsd))
            Text("本月 " + displayNumber(snapshot.month.tokens) + " tokens · USD " + displayNumber(snapshot.month.costUsd))
            if ((snapshot.month.unpricedTokens ?: 0) > 0 || (snapshot.today.unpricedTokens ?: 0) > 0) {
                Text("费用为已定价部分，部分 tokens 无价格数据", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (snapshot.readings.isEmpty()) Text("上游暂未提供额度或余额")
    for (reading in snapshot.readings) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(reading.title(), style = MaterialTheme.typography.titleMedium)
                Text(reading.value())
                reading.remainingPercent?.let { LinearProgressIndicator(progress = { (it / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth()) }
                reading.boundaryAt?.let {
                    Text((if (reading.boundaryKind == "expiry") "到期 " else if (reading.boundaryKind == "mixed") "重置 / 到期 " else "重置 ") + displayClock(it))
                }
                if (stale || reading.stale || locallyStale(reading.observedAt, snapshot.freshnessMs)) Text("可能过期 · " + displayClock(reading.observedAt))
            }
        }
    }
}

@Composable
private fun WatchSettings(watch: WatchConfig, connected: Boolean, hasPhoneLink: Boolean, readings: List<Reading>, busy: Boolean,
    onChange: (WatchConfig) -> Unit, onCode: () -> Unit, onRemove: () -> Unit) {
    var pinOpen by remember(watch.id) { mutableStateOf(false) }
    var rateOpen by remember(watch.id) { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(watch.label, style = MaterialTheme.typography.titleLarge)
            Text(if (connected) "手机连接在线" else "手机连接离线")
            val report = watch.report
            val status = if (report == null || report.appliedVersion != watch.version) "待手表确认"
            else when (report.state) {
                WatchStatus.READY -> "已同步"
                WatchStatus.PAUSED -> "已暂停"
                WatchStatus.NEEDS_PAIRING -> "等待直连授权"
                WatchStatus.WAITING -> "等待数据"
                WatchStatus.OFFLINE -> "更新失败"
            }
            Text("状态 · " + status + " · v" + watch.version)
            report?.snapshotAt?.let { Text("快照 " + displayClock(it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(watch.mode == Mode.PHONE, { onChange(watch.copy(mode = Mode.PHONE)) }, { Text("手机同步") }, enabled = !busy && hasPhoneLink && watch.mode != Mode.PHONE)
                FilterChip(watch.mode == Mode.DIRECT, { onChange(watch.copy(mode = Mode.DIRECT)) }, { Text("独立直连") }, enabled = !busy && watch.mode != Mode.DIRECT)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("暂停用量同步", modifier = Modifier.weight(1f))
                Switch(checked = watch.paused, onCheckedChange = { onChange(watch.copy(paused = it)) }, enabled = !busy)
            }
            Box {
                OutlinedButton({ pinOpen = true }, enabled = !busy) {
                    Text(readings.find { it.id == watch.pinnedId }?.title() ?: if (watch.pinnedId == null) "置顶额度 · 自动" else "置顶额度 · 上游暂缺")
                }
                DropdownMenu(pinOpen, { pinOpen = false }) {
                    DropdownMenuItem(text = { Text("自动选择") }, onClick = { pinOpen = false; onChange(watch.copy(pinnedId = null)) })
                    readings.forEach { row ->
                        DropdownMenuItem(text = { Text(row.title()) }, onClick = { pinOpen = false; onChange(watch.copy(pinnedId = row.id)) })
                    }
                }
            }
            if (watch.mode == Mode.DIRECT) {
                Box {
                    OutlinedButton({ rateOpen = true }, enabled = !busy) { Text("后台检查 · " + watch.refreshMinutes + " 分钟") }
                    DropdownMenu(rateOpen, { rateOpen = false }) {
                        listOf(15L, 30L, 60L).forEach { minutes ->
                            DropdownMenuItem(text = { Text(minutes.toString() + " 分钟") },
                                onClick = { rateOpen = false; onChange(watch.copy(refreshMinutes = minutes)) })
                        }
                    }
                }
                TextButton(onClick = onCode, enabled = !busy) { Text("获取直连配对码") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ onChange(watch.copy(refreshRequest = watch.refreshRequest + 1)) }, enabled = !busy && !watch.paused) { Text("请求刷新") }
                TextButton(onClick = onRemove, enabled = !busy) { Text("移除") }
            }
        }
    }
}

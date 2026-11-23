package io.github.jerryskywalker.tmcomp.mobile

import android.os.Bundle
import android.content.Intent
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.jerryskywalker.tmcomp.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val ledgerColors = lightColorScheme(
    primary = Color(0xff09634a), onPrimary = Color.White, primaryContainer = Color(0xffd7f1e6),
    onPrimaryContainer = Color(0xff102e25), background = Color(0xffeef6f7), surface = Color(0xfff9fdfd),
    onBackground = Color(0xff132e27), onSurface = Color(0xff132e27), outlineVariant = Color(0xffd6e6e1),
    secondaryContainer = Color(0xffcceee0), onSecondaryContainer = Color(0xff123c30),
    surfaceVariant = Color(0xffe4efee), onSurfaceVariant = Color(0xff3b5952),
)

class MainActivity : ComponentActivity() {
    private val launchSelection = MutableStateFlow<WidgetSelection?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchSelection.value = widgetSelection(intent)
        setContent {
            val storage = remember { DeviceStorage(this) }
            var theme by remember { mutableStateOf(storage.read<AppTheme>(THEME_KEY) ?: AppTheme.SYSTEM) }
            val colors = theme.colors()
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = colors.background.luminance() > .5f
                controller.isAppearanceLightNavigationBars = colors.surface.luminance() > .5f
            }
            MaterialTheme(colorScheme = colors) {
                val selection by launchSelection.collectAsState()
                PhoneScreen(selection, theme, { next ->
                    storage.write(THEME_KEY, next)
                    theme = next
                }) { launchSelection.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchSelection.value = widgetSelection(intent)
    }

    private fun widgetSelection(intent: Intent): WidgetSelection? = intent.getStringExtra(WIDGET_PAYLOAD)?.let {
        runCatching { wireJson.decodeFromString<WidgetSelection>(it) }.getOrNull()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneScreen(widget: WidgetSelection?, theme: AppTheme, setTheme: (AppTheme) -> Unit, consumed: () -> Unit) {
    val context = LocalContext.current
    val sync = remember { PhoneSync(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(sync.state()) }
    var credentials by remember { mutableStateOf(sync.storage.credentials()) }
    var busy by remember { mutableStateOf(false) }
    var hubReachable by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var screen by rememberSaveable { mutableStateOf("") }
    var toolId by rememberSaveable { mutableStateOf<String?>(null) }
    var quotaAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var watchId by rememberSaveable { mutableStateOf<String?>(null) }
    var watchHubParent by rememberSaveable { mutableStateOf("wear") }
    var watchDisplayParent by rememberSaveable { mutableStateOf("wear") }
    var limitKind by rememberSaveable { mutableStateOf("quota") }
    var overviewLayout by remember {
        mutableStateOf(sync.storage.read<OverviewLayout>(OVERVIEW_LAYOUT_KEY) ?: OverviewLayout.STANDARD)
    }
    var quotaLayout by remember {
        mutableStateOf(sync.storage.read<QuotaLayout>(QUOTA_LAYOUT_KEY) ?: QuotaLayout.STANDARD)
    }
    var pinnedRanges by remember {
        mutableStateOf(sync.storage.read<List<Range>>(OVERVIEW_RANGES_KEY)
            ?.filter { it in rangeChoices }?.distinct()?.take(4).orEmpty().ifEmpty { defaultRanges })
    }
    var range by rememberSaveable { mutableStateOf(pinnedRanges.first().name) }
    var activityRange by rememberSaveable { mutableStateOf<Range?>(null) }
    var collector by rememberSaveable { mutableStateOf<String?>(null) }
    var layout by remember { mutableStateOf(cards(context)) }
    var quotaOrders by remember { mutableStateOf(sync.storage.read<Map<String, List<String>>>(QUOTA_ORDER_KEY).orEmpty()) }
    var trendStyle by remember { mutableStateOf(sync.storage.read<TrendStyle>(TREND_STYLE_KEY) ?: TrendStyle.LINE) }
    var trendIntervals by remember { mutableStateOf(sync.storage.read<TrendIntervals>(TREND_INTERVAL_KEY) ?: TrendIntervals()) }
    var favorites by remember { mutableStateOf(sync.storage.read<List<FavoriteQuota>>(FAVORITE_QUOTAS_KEY)) }
    var favoriteDisplay by remember { mutableStateOf(sync.storage.read<FavoriteQuotaDisplay>(FAVORITE_QUOTA_DISPLAY) ?: FavoriteQuotaDisplay()) }
    var collectorDisplay by remember { mutableStateOf(sync.storage.read<CollectorDisplay>(COLLECTOR_DISPLAY) ?: CollectorDisplay()) }
    var candleColors by remember { mutableStateOf(sync.storage.read<CandleColors>(CANDLE_COLORS) ?: CandleColors.GREEN_UP) }
    var watchPreset by remember { mutableStateOf(sync.storage.read<WatchDisplay>(WATCH_DISPLAY_PRESET_KEY) ?: WatchDisplay()) }
    val dashboardScope = DashboardScope(Range.valueOf(range), collector)
    val activityScope = dashboardScope.copy(range = activityRange ?: dashboardScope.range)
    val interval = trendIntervals.forRange(dashboardScope.range)
    val snapshot = state.snapshot
    val period = snapshot?.period(dashboardScope)
    val stale = state.usageError != null || snapshot?.let { it.stale || locallyStale(it.upstreamAt, it.freshnessMs) } == true

    fun refreshWidgets() {
        scope.launch {
            try {
                DashboardWidget().updateAll(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // WorkManager will also update widgets after syncing fresh usage.
            }
        }
    }

    fun setScope(value: DashboardScope) {
        range = value.range.name
        collector = value.device
    }
    fun showCard(card: OverviewCard, id: String?) {
        toolId = id
        when (card) {
            OverviewCard.SUMMARY, OverviewCard.COMPOSITION -> { tab = 0; screen = "composition" }
            OverviewCard.TREND -> { tab = 2; screen = ""; activityRange = null }
            OverviewCard.TOOLS -> { tab = 0; screen = if (id == null) "tools" else "tool" }
            OverviewCard.QUOTAS, OverviewCard.WALLETS -> {
                tab = 1; screen = ""; limitKind = if (card == OverviewCard.WALLETS) "balance" else "quota"
            }
        }
    }

    LaunchedEffect(widget) {
        widget?.let {
            setScope(it.scope)
            showCard(it.card, null)
            consumed()
        }
    }
    fun back() {
        screen = when (screen) {
            "tool" -> "tools"
            "editor", "overviewRanges", "favorites" -> if (tab == 4) "overviewSettings" else ""
            "watch" -> "wear"
            "watchHub" -> watchHubParent
            "watchDisplay" -> watchDisplayParent
            "watchPreset" -> "wear"
            "hubEdit" -> "server"
            else -> ""
        }
    }
    BackHandler(screen.isNotEmpty()) { back() }
    var confirmRemove by remember { mutableStateOf<WatchConfig?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var watchCode by remember { mutableStateOf<WatchPairingCode?>(null) }
    var scanDisplay by rememberSaveable { mutableStateOf(false) }
    var importedWatchHub by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            runCatching { WatchPairingCode.parse(contents) }
                .onSuccess { watchCode = it; error = null }
                .onFailure { error = it.message }
        }
    }

    fun scanWatch(display: Boolean = false) {
        error = null
        scanDisplay = display
        val options = ScanOptions().setCaptureActivity(WatchQrActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setOrientationLocked(false).setBeepEnabled(false).setBarcodeImageEnabled(false)
            .setPrompt(if (display) "扫描手表「从手机导入显示设置」中的配对码" else "扫描手表「从手机导入 Hub」中的配对码")
        try {
            scanner.launch(options)
        } catch (_: ActivityNotFoundException) {
            error = "此设备无法打开扫码相机"
        }
    }

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

    fun checkConnection() = act {
        try {
            sync.refresh()
            hubReachable = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            hubReachable = false
        }
    }

    LaunchedEffect(sync) {
        sync.changes().collect { next ->
            state = next
            if (next.snapshot != null || next.usageError != null) hubReachable = next.usageError == null
        }
    }
    LaunchedEffect(lifecycle, credentials?.origin, credentials?.secret) {
        val connection = credentials
        if (connection == null) {
            hubReachable = null
            return@LaunchedEffect
        }
        PhoneSync.schedule(context)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { sync.watchUpdates().collect { sync.observeWatches() } }
            Hub(connection).refreshWhileVisible(60_000) {
                try {
                    sync.refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The state stream exposes connection failures while retaining cached usage.
                }
            }
        }
    }
    val showingWear = screen in setOf("wear", "watch", "watchDisplay", "watchHub")
    LaunchedEffect(lifecycle, showingWear) {
        if (!showingWear) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                sync.observeWatches()
                delay(4_000)
            }
        }
    }

    val tabs = listOf("总览", "额度", "活动", "设备", "设置")
    val icons = listOf(R.drawable.nav_home, R.drawable.nav_quota, R.drawable.nav_activity, R.drawable.settings_watch, R.drawable.nav_settings)
    val scroll = rememberScrollState()
    val inSettings = credentials != null && tab == 4
    val detailTitle = when (screen) {
        "overviewSettings" -> "总览设置"
        "appearance" -> "外观与图表"
        "about" -> "关于"
        "editor" -> "编辑总览"
        "overviewRanges" -> "总览时间筛选"
        "quotaLayout" -> "额度页布局"
        "favorites" -> "关注额度"
        "collectors" -> "Hub 采集设备"
        "wear", "watch" -> "Wear OS"
        "watchDisplay", "watchPreset" -> "手表显示设置"
        "server" -> "Hub 详情"
        "hubEdit" -> "编辑手机 Hub"
        "watchHub" -> "手表 Hub 直连设置"
        "quotaDetail" -> "账户额度明细"
        "quotaOrder" -> "调整账户顺序"
        "activityTool", "tool" -> "工具明细"
        "tools" -> "我的工具"
        "composition" -> "用量明细"
        else -> null
    }
    val compactQuota = credentials != null && screen.isEmpty() && tab == 1 && quotaLayout == QuotaLayout.COMPACT
    val activityRoot = credentials != null && screen.isEmpty() && tab == 2
    val overview = credentials != null && screen.isEmpty() && tab == 0
    val compactOverview = overview && overviewLayout == OverviewLayout.COMPACT
    val title = when {
        credentials == null -> "连接 Token Monitor Hub"
        detailTitle != null -> detailTitle
        else -> listOf("Token Monitor", "额度与余额", "活动", "设备", "设置")[tab]
    }
    LaunchedEffect(tab, screen) { scroll.scrollTo(0) }
    Scaffold(topBar = {
        PhoneTopBar(title, onBack = if (detailTitle == null) null else ::back) {
            when {
                overview -> {
                    TextButton(::checkConnection, enabled = !busy, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("刷新")
                    }
                    TextButton({ screen = "editor" }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("编辑总览")
                    }
                }
                activityRoot -> ActivityDevicePicker(snapshot, activityScope.device) { collector = it }
                credentials != null && screen.isEmpty() && tab == 1 && limitKind != "subscriptions" -> {
                    val canSort = remember(snapshot, limitKind) { snapshot.quotaOrder(limitKind, emptyList()).size > 1 }
                    TextButton({ screen = "quotaOrder" }, enabled = canSort) { Text("调整顺序") }
                }
            }
        }
    }, bottomBar = {
        if (credentials != null && screen.isEmpty()) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            tabs.forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { SettingsIcon(icons[index]) },
                    label = { Text(label) })
            }
        }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactQuota || compactOverview) 5.dp else 10.dp),
        ) {
            if (busy && !inSettings && !activityRoot) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (credentials == null) {
                HubForm(startAddress = "", busy = busy, submit = "连接 Hub") { address, secret ->
                    act {
                        sync.connect(hubConnection(address, secret, BuildConfig.DEBUG))
                        hubReachable = true
                    }
                }
            } else {
                when (screen) {
                    "overviewSettings" -> OverviewSettings(
                        overviewLayout,
                        onLayout = { next ->
                            sync.storage.write(OVERVIEW_LAYOUT_KEY, next)
                            overviewLayout = next
                        },
                        onCards = { screen = "editor" },
                        onRanges = { screen = "overviewRanges" },
                        onFavorites = { screen = "favorites" },
                    )
                    "appearance" -> AppearanceSettings(theme, candleColors,
                        onTheme = {
                            setTheme(it)
                            refreshWidgets()
                        },
                        onCandleColors = { next ->
                            sync.storage.write(CANDLE_COLORS, next)
                            candleColors = next
                            refreshWidgets()
                        })
                    "about" -> AboutSettings()
                    "editor" -> EditOverview(layout, { next ->
                        sync.storage.write(OVERVIEW_QUEUE_KEY, next)
                        layout = next
                        back()
                    }, ::back)
                    "overviewRanges" -> EditOverviewRanges(pinnedRanges, { next ->
                        sync.storage.write(OVERVIEW_RANGES_KEY, next)
                        if (dashboardScope.range !in next) range = next.first().name
                        pinnedRanges = next
                        back()
                    }, ::back)
                    "favorites" -> EditFavoriteQuotas(snapshot, favorites, favoriteDisplay) { next, display ->
                        sync.storage.write(FAVORITE_QUOTAS_KEY, next)
                        sync.storage.write(FAVORITE_QUOTA_DISPLAY, display)
                        favorites = next
                        favoriteDisplay = display
                        refreshWidgets()
                        back()
                    }
                    "collectors" -> CollectorSettings(snapshot, collectorDisplay) { next ->
                        sync.storage.write(COLLECTOR_DISPLAY, next)
                        collectorDisplay = next
                    }
                    "quotaDetail" -> QuotaDetail(snapshot.quotaOrder(limitKind, emptyList()).firstOrNull { it.sortId == quotaAccountId },
                        state.quotaScope, snapshot?.retrievedAt)
                    "activityTool" -> {
                        Text(activityScope.range.label, style = MaterialTheme.typography.titleMedium)
                        snapshot?.let { data ->
                            val usage = data.period(activityScope)
                            tools(data, usage).firstOrNull { it.id == toolId }?.let {
                                ToolDetail(it, usage, data.freshnessMs, stale)
                            }
                        }
                    }
                    "quotaOrder" -> {
                        EditQuotaOrder(snapshot.quotaOrder(limitKind, quotaOrders[limitKind].orEmpty()),
                            onSave = { ids ->
                                val prior = quotaOrders[limitKind].orEmpty()
                                quotaOrders = quotaOrders + (limitKind to (ids + prior).distinct())
                                sync.storage.write(QUOTA_ORDER_KEY, quotaOrders)
                                screen = ""
                            }, onCancel = ::back)
                    }
                    "tools" -> {
                        ScopePicker(snapshot, dashboardScope, ::setScope, pinnedRanges)
                        snapshot?.let { data ->
                            tools(data, period).forEach { tool ->
                                TextButton({ toolId = tool.id; screen = "tool" }) {
                                    Column {
                                        Text(tool.label + " · " + displayNumber(tool.usage?.tokens) + " Tokens")
                                        if (tool.readings.isEmpty()) Text(tool.availability(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    "tool" -> {
                        ScopePicker(snapshot, dashboardScope, ::setScope, pinnedRanges)
                        snapshot?.let { data ->
                            tools(data, period).firstOrNull { it.id == toolId }?.let {
                                ToolDetail(it, period, data.freshnessMs, stale)
                            }
                        }
                    }
                    "composition" -> {
                        ScopePicker(snapshot, dashboardScope, ::setScope, pinnedRanges)
                        LedgerLine("Tokens", displayNumber(period?.tokens))
                        LedgerLine("预估成本", money(period?.costUsd))
                        LedgerLine("未计价 Tokens", displayNumber(period?.unpricedTokens))
                        components(period).forEach { (label, value) -> LedgerLine(label, displayNumber(value)) }
                    }
                    "quotaLayout" -> QuotaLayoutSettings(quotaLayout, snapshot, stale) { next ->
                        sync.storage.write(QUOTA_LAYOUT_KEY, next)
                        quotaLayout = next
                    }
                    "server" -> ServerSettings(credentials?.origin.orEmpty(), hubReachable, snapshot, stale,
                        state.usageError, busy, ::checkConnection, onEdit = { screen = "hubEdit" }, onClear = { confirmClear = true })
                    "hubEdit" -> HubForm(credentials?.origin.orEmpty(), busy, "保存手机 Hub") { address, secret ->
                        act {
                            sync.connect(hubConnection(address, secret, BuildConfig.DEBUG))
                            hubReachable = true
                            screen = "server"
                        }
                    }
                    "watchHub" -> state.watches.firstOrNull { it.id == watchId }?.let { watch ->
                        val connected = watch.id in state.connected
                        Text("此地址和 Secret 保存到手表。独立直连时，手表直接向这个 Hub 取数。")
                        if (!connected) Text("请让手表与手机保持连接。")
                        OutlinedButton({
                            act {
                                sync.configureWatchWithPhoneHub(watch)
                                screen = watchHubParent
                            }
                        }, enabled = !busy && connected) { Text("使用手机的 Hub 地址和 Secret") }
                        HubForm(watch.report?.hubOrigin ?: credentials?.origin.orEmpty(), busy || !connected,
                            "发送到手表") { address, secret ->
                            act {
                                sync.configureWatchHub(watch, hubConnection(address, secret, BuildConfig.DEBUG))
                                screen = watchHubParent
                            }
                        }
                        TextButton(onClick = { screen = watchHubParent }, enabled = !busy) { Text("返回手表设置") }
                    }
                    "watchDisplay" -> state.watches.firstOrNull { it.id == watchId }?.let { watch ->
                        val rows = if (watch.mode == Mode.DIRECT) watch.report?.readings.orEmpty() else snapshot?.readings.orEmpty()
                        WatchDisplayEditor(watch.display, rows, snapshot, busy, onSave = { next ->
                            act {
                                sync.edit(watch, watch.copy(display = next))
                                screen = watchDisplayParent
                            }
                        })
                    }
                    "watchPreset" -> WatchDisplayEditor(watchPreset, snapshot?.readings.orEmpty(), snapshot, busy,
                        onSave = { next ->
                            sync.storage.write(WATCH_DISPLAY_PRESET_KEY, next)
                            watchPreset = next
                            screen = "wear"
                        }, onScan = { next ->
                            sync.storage.write(WATCH_DISPLAY_PRESET_KEY, next)
                            watchPreset = next
                            scanWatch(display = true)
                        })
                    "wear", "watch" -> {
                        if (screen == "wear") {
                            WearPairingEntry(busy, state.wearAvailable == false, onScan = { scanWatch() })
                            SettingsGroup {
                                SettingRow("独立手表显示预设", "实时预览并扫码发送", R.drawable.settings_watch,
                                    onClick = { screen = "watchPreset" })
                            }
                        }
                        val watch = state.watches.firstOrNull { screen == "watch" && it.id == watchId }
                        watch?.let {
                            val readings = if (it.mode == Mode.DIRECT) it.report?.readings.orEmpty() else snapshot?.readings.orEmpty()
                            WatchSettings(it, it.id in state.connected, readings, busy,
                                onChange = { next -> act { sync.edit(it, next) } },
                                onHub = { watchHubParent = screen; watchId = it.id; screen = "watchHub" },
                                onDisplay = { watchDisplayParent = screen; watchId = it.id; screen = "watchDisplay" },
                                onRemove = { confirmRemove = it })
                        }
                        if (screen == "wear") {
                            val available = state.available.filter { device ->
                                state.watches.none { it.id == device.id } || device.id !in state.connected
                            }
                            WearDevices(state.watches, available, state.connected, busy,
                                onOpen = { watchId = it.id; screen = "watch" },
                                onDiscover = { act { sync.discover() } },
                                onBind = { device ->
                                    act {
                                        sync.bind(device)
                                        watchId = device.id
                                        watchHubParent = "wear"
                                        screen = "watchHub"
                                    }
                                })
                        } else if (watch == null) {
                            Text("此手表已移除")
                        }
                    }
                    else -> when (tab) {
                        0 -> {
                            ScopePicker(snapshot, dashboardScope, ::setScope, pinnedRanges, header = {
                                Column(Modifier.weight(1f)) {
                                    OverviewFreshness(snapshot, stale, state.usageError != null, busy)
                                }
                            })
                            if (snapshot != null) {
                                if (layout.isEmpty()) Text("总览没有可见卡片。可从编辑总览添加。")
                                layout.forEach { LedgerCard(it, snapshot, dashboardScope, trendStyle, interval, favorites, favoriteDisplay, candleColors, overviewLayout,
                                    { screen = "favorites" }, ::showCard) }
                            }
                        }
                        1 -> QuotaScreen(snapshot, stale, limitKind, quotaLayout, quotaOrders[limitKind].orEmpty(),
                            state.subscriptions,
                            onAccount = { id -> quotaAccountId = id; screen = "quotaDetail" }) { limitKind = it }
                        2 -> ActivityScreen(snapshot, activityScope, pinnedRanges, trendStyle,
                            trendIntervals.forRange(activityScope.range), candleColors,
                            onScope = { next ->
                                activityRange = next.range.takeIf { it.rollingHours > 0 }
                                if (activityRange == null) setScope(next) else collector = next.device
                            }, onStyle = { next ->
                                trendStyle = next
                                sync.storage.write(TREND_STYLE_KEY, next)
                                refreshWidgets()
                            }, onInterval = { next ->
                                trendIntervals = trendIntervals.withRange(activityScope.range, next)
                                sync.storage.write(TREND_INTERVAL_KEY, trendIntervals)
                                refreshWidgets()
                            }, onTool = { toolId = it; screen = "activityTool" })
                        3 -> DevicesTab(snapshot, collectorDisplay, onDevice = { device ->
                            collector = device
                            tab = 0
                        }, onWear = {
                            tab = 4
                            screen = "wear"
                        })
                        else -> SettingsHome(credentials?.origin.orEmpty(), hubReachable, quotaLayout,
                            overviewLayout = overviewLayout, theme = theme, watchCount = state.watches.size,
                            onServer = { screen = "server" },
                            onOverview = { screen = "overviewSettings" },
                            onQuotaLayout = { screen = "quotaLayout" },
                            onAppearance = { screen = "appearance" },
                            collectorLayout = collectorDisplay.layout,
                            onCollectors = { screen = "collectors" },
                            onWidget = {
                                if (!requestOverviewWidget(context)) {
                                    Toast.makeText(context, "请从主屏幕的小工具面板添加", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onWear = { screen = "wear" },
                            onAbout = { screen = "about" })
                    }
                }
            }
        }
    }
    val hub = credentials
    watchCode?.takeIf { scanDisplay || hub != null }?.let { code ->
        if (scanDisplay) {
            AlertDialog(onDismissRequest = { watchCode = null }, title = { Text("发送手表显示设置？") },
                text = { Text("将额度顺序、趋势偏好和 OLED 设置发送到这块手表。") },
                dismissButton = { TextButton({ watchCode = null }) { Text("取消") } },
                confirmButton = {
                    Button(enabled = !busy, onClick = {
                        watchCode = null
                        act {
                            val reply = sendWatchDisplay(context, code, watchPreset)
                            require(reply.displayApplied) { reply.error ?: "手表未确认显示设置" }
                            Toast.makeText(context, "手表已应用显示设置", Toast.LENGTH_SHORT).show()
                            screen = "wear"
                        }
                    }) { Text("发送显示设置") }
                })
        } else {
        AlertDialog(onDismissRequest = { watchCode = null }, title = { Text("发送 Hub 配置到手表？") },
            text = { Text("将手机保存的 Hub 地址和 Secret 发送到刚刚扫码的手表。手表验证后即可独立直连。\n\n" + hub?.origin.orEmpty()) },
            dismissButton = { TextButton({ watchCode = null }) { Text("取消") } },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    watchCode = null
                    act {
                        val savedHub = requireNotNull(sync.storage.credentials()) { "请先配置手机的 Hub" }
                        val reply = sendWatchHub(context, code, savedHub)
                        require(reply.origin == savedHub.origin) { reply.error ?: "手表未确认 Hub 配置" }
                        importedWatchHub = savedHub.origin
                    }
                }) { Text("发送 Hub 配置") }
            })
        }
    }
    importedWatchHub?.let { address ->
        AlertDialog(onDismissRequest = { importedWatchHub = null }, title = { Text("手表已验证 Hub") },
            text = { Text("手表已保存配置并可独立取数。\n\n" + address) },
            confirmButton = { TextButton({ importedWatchHub = null }) { Text("完成") } })
    }
    confirmRemove?.let { watch ->
        AlertDialog(onDismissRequest = { confirmRemove = null }, title = { Text("移除 " + watch.label) },
            text = { Text("手表收到解绑后会清除本机 Hub 配置和快照。") }, dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消") }
            }, confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    act {
                        sync.remove(watch)
                        if (screen == "watch" && watchId == watch.id) screen = "wear"
                    }
                }) { Text("移除") }
            })
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除手机上的 Hub 连接？") },
            text = { Text("本机会清除 Hub 地址、Secret 和缓存。再次连接时请重新填写。") },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    act {
                        sync.clear()
                        screen = ""
                        tab = 0
                    }
                }) { Text("清除") }
            })
    }
}

@Composable
private fun OverviewFreshness(snapshot: Snapshot?, cached: Boolean, failed: Boolean, updating: Boolean) {
    val warning = failed || cached
    val label = when {
        cached && snapshot != null -> "等待刷新 · 使用缓存"
        failed -> "Hub 暂不可达 · 等待刷新"
        snapshot == null || updating -> "等待数据刷新"
        else -> "Hub · " + displayClock(snapshot.upstreamAt)
    }
    val color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (warning) Icon(painterResource(R.drawable.status_warning), null, Modifier.size(14.dp), tint = color)
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

package io.github.jerryskywalker.tmcomp.mobile

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator as WidgetProgress
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column as WidgetColumn
import androidx.glance.layout.Row as WidgetRow
import androidx.glance.layout.fillMaxSize as widgetFill
import androidx.glance.layout.fillMaxWidth as widgetWidth
import androidx.glance.layout.height as widgetHeight
import androidx.glance.layout.padding as widgetPadding
import androidx.glance.text.FontWeight as WidgetWeight
import androidx.glance.text.Text as WidgetText
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.lifecycle.lifecycleScope
import io.github.jerryskywalker.tmcomp.core.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal const val WIDGET_PAYLOAD = "io.github.jerryskywalker.tmcomp.widget"

@Serializable
data class WidgetSelection(val card: OverviewCard = OverviewCard.SUMMARY, val scope: DashboardScope = DashboardScope())

private fun widgetKey(id: Int) = "dashboard_widget_$id"

class DashboardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(148.dp, 112.dp), DpSize(274.dp, 148.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val storage = DeviceStorage(context)
        val key = widgetKey(appWidgetId)
        fun read() = WidgetView(storage.read<WidgetSelection>(key) ?: WidgetSelection(),
            storage.read<PhoneState>(PHONE_VIEW_KEY), storage.read<TrendStyle>(TREND_STYLE_KEY) ?: TrendStyle.LINE,
            storage.read<TrendIntervals>(TREND_INTERVAL_KEY) ?: TrendIntervals(),
            storage.read<List<FavoriteQuota>>(FAVORITE_QUOTAS_KEY), storage.read<AppTheme>(THEME_KEY) ?: AppTheme.SYSTEM,
            storage.read<FavoriteQuotaDisplay>(FAVORITE_QUOTA_DISPLAY) ?: FavoriteQuotaDisplay(),
            storage.read<CandleColors>(CANDLE_COLORS) ?: CandleColors.GREEN_UP)
        val prefsKeys = setOf<String?>(key, PHONE_VIEW_KEY, TREND_STYLE_KEY, TREND_INTERVAL_KEY, FAVORITE_QUOTAS_KEY,
            THEME_KEY, FAVORITE_QUOTA_DISPLAY, CANDLE_COLORS)
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        provideContent {
            val widget by produceState(read(), storage.preferences, key) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, change ->
                    if (change in prefsKeys) value = read()
                }
                storage.preferences.registerOnSharedPreferenceChangeListener(listener)
                value = read()
                awaitDispose { storage.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val selection = widget.selection
            val interval = widget.intervals.forRange(selection.scope.range)
            val palette = widget.theme.colors(systemDark)
            val heading = TextStyle(color = ColorProvider(palette.onSurface), fontSize = 14.sp, fontWeight = WidgetWeight.Bold)
            val label = TextStyle(color = ColorProvider(palette.onSurfaceVariant), fontSize = 11.sp)
            val snapshot = widget.phone?.snapshot
            val stale = widget.phone?.usageError != null ||
                snapshot?.let { it.stale || locallyStale(it.upstreamAt, it.freshnessMs) } == true
            val chart = remember(snapshot, selection, widget.trendStyle, interval, palette, widget.candleColors) {
                snapshot?.let { widgetChart(selection, it, widget.trendStyle, interval, palette.primary, widget.candleColors,
                    palette == nightColors || palette == oledColors) }
            }
            val open = Intent(context, MainActivity::class.java).setData(Uri.parse("tmcomp://widget/$appWidgetId"))
                .putExtra(WIDGET_PAYLOAD, wireJson.encodeToString(selection))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val wide = LocalSize.current.width >= 240.dp
            WidgetColumn(GlanceModifier.widgetFill().background(palette.background).cornerRadius(18.dp)
                .clickable(actionStartActivity(open)).widgetPadding(10.dp)) {
                WidgetText(selection.card.title, style = heading, maxLines = 1)
                if (snapshot == null) WidgetText("请在应用中连接 Hub", style = label)
                else {
                    val lines = cardLines(selection.card, snapshot, selection.scope, widget.favorites, interval, widget.favoriteDisplay)
                    if (lines.isEmpty()) WidgetText("暂无读数", style = label)
                    if (selection.card == OverviewCard.SUMMARY) {
                        lines.forEach { (name, value) ->
                            val metric = if (name == "Tokens") displayCompactTokens(snapshot.period(selection.scope)?.tokens) else value
                            WidgetText("$name  $metric", style = heading, maxLines = 1)
                        }
                    } else if (selection.card == OverviewCard.QUOTAS) {
                        val bars = widget.favoriteDisplay.style == QuotaDisplayStyle.BAR
                        snapshot.favoriteLines(widget.favorites, widget.favoriteDisplay).take(if (bars) (if (wide) 4 else 2) else (if (wide) 5 else 3))
                            .forEach { row ->
                                WidgetRow(GlanceModifier.widgetWidth()) {
                                    WidgetText(row.label, GlanceModifier.defaultWeight(), style = label, maxLines = 1)
                                    WidgetText(row.value, style = label, maxLines = 1)
                                }
                                row.fill?.let { fill -> WidgetProgress(fill, GlanceModifier.widgetWidth().widgetHeight(3.dp),
                                    color = ColorProvider(palette.primary), backgroundColor = ColorProvider(palette.surfaceVariant)) }
                            }
                    } else {
                        if (chart != null) Image(ImageProvider(chart), lines.joinToString("，") { it.first + " " + it.second },
                            GlanceModifier.widgetWidth().widgetHeight(if (wide) 52.dp else 37.dp))
                        val limit = if (chart != null) (if (wide) 2 else 1) else (if (wide) 5 else 3)
                        val visible = if (selection.card == OverviewCard.TREND) lines.takeLast(limit) else lines.take(limit)
                        visible.forEach { (name, value) ->
                            WidgetRow(GlanceModifier.widgetWidth()) {
                                WidgetText(name, GlanceModifier.defaultWeight(), style = label, maxLines = 1)
                                WidgetText(value, style = label, maxLines = 1)
                            }
                        }
                    }
                    val scopeLabel = when (selection.card) {
                        OverviewCard.QUOTAS, OverviewCard.WALLETS -> "按账号采样"
                        else -> selection.scope.range.label
                    }
                    WidgetText(scopeLabel + " · " + if (stale) "缓存" else displayClock(snapshot.upstreamAt),
                        style = label, maxLines = 1)
                }
            }
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        DeviceStorage(context).write<WidgetSelection>(widgetKey(id), null)
    }
}

private data class WidgetView(val selection: WidgetSelection, val phone: PhoneState?, val trendStyle: TrendStyle,
    val intervals: TrendIntervals, val favorites: List<FavoriteQuota>?, val theme: AppTheme,
    val favoriteDisplay: FavoriteQuotaDisplay, val candleColors: CandleColors)

private fun widgetChart(selection: WidgetSelection, data: Snapshot, trendStyle: TrendStyle, interval: TrendInterval,
    accent: Color, candleColors: CandleColors, dark: Boolean): Bitmap? {
    val bitmap = when (selection.card) {
        OverviewCard.TREND -> {
            val series = data.trend(selection.scope, interval = interval)
            val candles = if (trendStyle == TrendStyle.CANDLE) series.candles(false) else emptyList()
            val peak = if (trendStyle == TrendStyle.CANDLE) candles.maxOfOrNull { it.high }
                else series.points.mapNotNull { it.tokens }.maxOrNull()?.toDouble()
            val max = peak?.coerceAtLeast(1.0) ?: return null
            Bitmap.createBitmap(250, 62, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = Canvas(target)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent.toArgb(); strokeWidth = 3f }
                fun y(value: Double) = 57f - (value / max).toFloat() * 51f
                if (trendStyle == TrendStyle.CANDLE) {
                    candles.forEach { candle ->
                        val x = 5f + candle.position * 240f
                        paint.color = candleColors.chartColor(candle.close >= candle.open, dark).toArgb()
                        canvas.drawLine(x, y(candle.high), x, y(candle.low), paint)
                        val top = y(maxOf(candle.open, candle.close))
                        canvas.drawRect(x - 3f, top, x + 3f, y(minOf(candle.open, candle.close)).coerceAtLeast(top + 1f), paint)
                    }
                    return@also
                }
                var prior: Pair<Float, Float>? = null
                series.points.forEach { point ->
                    val value = point.tokens
                    if (value == null) prior = null
                    else {
                        val x = 5f + point.position * 240f
                        val y = y(value.toDouble())
                        prior?.let { (px, py) ->
                            paint.color = accent.copy(alpha = .15f).toArgb()
                            canvas.drawPath(Path().apply {
                                moveTo(px, 58f); lineTo(px, py); lineTo(x, y); lineTo(x, 58f); close()
                            }, paint)
                            paint.color = accent.toArgb()
                            canvas.drawLine(px, py, x, y, paint)
                        }
                        canvas.drawCircle(x, y, 2.5f, paint)
                        prior = x to y
                    }
                }
            }
        }
        OverviewCard.COMPOSITION -> {
            val parts = components(data.period(selection.scope)).map { it.second ?: 0 }
            val total = parts.sum().takeIf { it > 0 } ?: return null
            val colors = listOf(0xff087451, 0xff888cdb, 0xff519bb0, 0xff97c889, 0xff8d9897)
            Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = Canvas(target)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f }
                val bounds = RectF(6f, 6f, 54f, 54f)
                var start = -90f
                parts.forEachIndexed { index, part ->
                    val sweep = 360f * part / total
                    paint.color = colors[index].toInt()
                    canvas.drawArc(bounds, start, sweep, false, paint)
                    start += sweep
                }
            }
        }
        else -> null
    }
    return bitmap
}

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = DashboardWidget()
}

internal fun requestOverviewWidget(context: Context): Boolean {
    val manager = AppWidgetManager.getInstance(context)
    return manager.isRequestPinAppWidgetSupported &&
        manager.requestPinAppWidget(ComponentName(context, DashboardWidgetReceiver::class.java), null, null)
}

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(Activity.RESULT_CANCELED, result)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (info?.provider != ComponentName(this, DashboardWidgetReceiver::class.java)) {
            finish()
            return
        }
        val storage = DeviceStorage(this)
        val saved = storage.read<WidgetSelection>(widgetKey(id)) ?: WidgetSelection()
        val snapshot = storage.read<PhoneState>(PHONE_VIEW_KEY)?.snapshot
        val theme = storage.read<AppTheme>(THEME_KEY) ?: AppTheme.SYSTEM
        setContent {
            MaterialTheme(colorScheme = theme.colors()) {
                var selection by remember { mutableStateOf(saved) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageTitle("选择桌面卡片")
                    OverviewCard.entries.forEach { card ->
                        FilterChip(selection.card == card, { selection = selection.copy(card = card) },
                            { Text(card.title) }, enabled = !busy)
                    }
                    if (selection.card !in setOf(OverviewCard.WALLETS, OverviewCard.QUOTAS)) {
                        ScopePicker(snapshot, selection.scope, change = { selection = selection.copy(scope = it) })
                    }
                    if (error) Text("小工具更新失败，请重试。")
                    Button(enabled = !busy, onClick = {
                        busy = true
                        lifecycleScope.launch {
                            val manager = GlanceAppWidgetManager(this@WidgetConfigurationActivity)
                            try {
                                storage.write(widgetKey(id), selection)
                                DashboardWidget().update(this@WidgetConfigurationActivity, manager.getGlanceIdBy(id))
                                setResult(Activity.RESULT_OK, result)
                                finish()
                            } catch (failure: kotlinx.coroutines.CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                storage.write(widgetKey(id), saved)
                                error = true
                                busy = false
                            }
                        }
                    }) { Text("保存小工具") }
                }
            }
        }
    }
}

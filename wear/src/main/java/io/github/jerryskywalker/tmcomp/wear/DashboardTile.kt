package io.github.jerryskywalker.tmcomp.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import io.github.jerryskywalker.tmcomp.core.DeviceStorage
import io.github.jerryskywalker.tmcomp.core.Mode
import io.github.jerryskywalker.tmcomp.core.displayClock
import io.github.jerryskywalker.tmcomp.core.displayCompactTokens
import io.github.jerryskywalker.tmcomp.core.locallyStale
import io.github.jerryskywalker.tmcomp.core.value

class DashboardTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) =
        ready(buildTile())

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        ready(Resources.Builder().setVersion(RESOURCE_VERSION).build())

    private fun buildTile(): Tile {
        val storage = DeviceStorage(this)
        val view = storage.read<WatchView>(WATCH_VIEW) ?: WatchView()
        val snapshot = view.snapshot
        val cached = view.config?.paused == true || view.error != null ||
            snapshot?.let { it.stale || locallyStale(it.upstreamAt, it.freshnessMs) } == true
        val choice = storage.read<String>("watch_ring_provider")
        val pin = snapshot?.readings?.firstOrNull { it.id == view.config?.pinnedId }
        val ring = (choice ?: pin?.toolKey)?.takeIf { it.isNotEmpty() }
        val quota = snapshot?.readings.orEmpty().filter { it.toolKey == ring && it.kind == "quota" && it.shortWindow }
            .filter { !it.stale && !locallyStale(it.observedAt, snapshot?.freshnessMs ?: 0) }
            .minByOrNull { it.remainingPercent ?: 101.0 }
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open-dashboard")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name).build()).build())
            .build()
        val text = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(line("Tokens · 今天", 12f))
            .addContent(line(displayCompactTokens(snapshot?.today?.tokens), 23f))
            .addContent(line("本月 " + displayCompactTokens(snapshot?.month?.tokens), 13f))
        if (ring != null) text.addContent(line(
            (quota?.provider ?: pin?.provider ?: ring) + " · " + if (cached) "—" else quota?.value() ?: "—", 11f))
        val status = when {
            snapshot == null -> "等待用量"
            cached -> "离线缓存"
            view.config?.mode == Mode.DIRECT -> "独立直连"
            else -> "手机推送"
        }
        text.addContent(line(status + " · " + displayClock(snapshot?.upstreamAt), 10f))
            .addContent(line("打开看板 ›", 12f, ModifiersBuilders.Modifiers.Builder().setClickable(open).build()))
        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(text.build()).build()
        return Tile.Builder().setResourcesVersion(RESOURCE_VERSION)
            .setFreshnessIntervalMillis(15 * 60 * 1000L).setTileTimeline(Timeline.fromLayoutElement(layout)).build()
    }

    private fun line(label: String, size: Float, modifiers: ModifiersBuilders.Modifiers? = null) =
        LayoutElementBuilders.Text.Builder().setText(label).setMaxLines(1).also { text ->
            modifiers?.let { text.setModifiers(it) }
        }.setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(size))
            .setColor(argb(0xffc7e1ff.toInt())).build()).build()

    private fun <T> ready(value: T) = CallbackToFutureAdapter.getFuture<T> { result ->
        result.set(value)
        "Token Monitor Tile"
    }

    private companion object { const val RESOURCE_VERSION = "1" }
}

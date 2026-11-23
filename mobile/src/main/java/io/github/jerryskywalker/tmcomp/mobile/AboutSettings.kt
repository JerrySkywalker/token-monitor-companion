package io.github.jerryskywalker.tmcomp.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AboutSettings() {
    val browser = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReleaseCheck?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun open(link: String) {
        try {
            browser.openUri(link)
        } catch (_: IllegalArgumentException) {
            message = "此设备无法打开网页"
        }
    }

    SettingsGroup {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(io.github.jerryskywalker.tmcomp.core.R.drawable.tmcomp_logo), null,
                Modifier.size(44.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Token Monitor Companion", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                val channel = if (BuildConfig.DEBUG) " Debug" else ""
                Text("当前版本 v${BuildConfig.VERSION_NAME}$channel · MIT 许可证",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsDivider()
        SettingRow("GitHub 源码", "JerrySkywalker/token-monitor-companion", onClick = { open(COMPANION_GITHUB) })
        SettingsDivider()
        SettingRow("检查更新", enabled = !checking, onClick = {
            checking = true
            result = null
            message = null
            scope.launch {
                try {
                    result = checkLatestRelease(BuildConfig.VERSION_NAME)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ReleaseRateLimited) {
                    message = "GitHub 暂时限制了请求次数，可以直接打开发布记录"
                } catch (_: Exception) {
                    message = "暂时无法检查更新，请稍后重试"
                } finally {
                    checking = false
                }
            }
        })
        val status = when {
            checking -> "正在查询 GitHub Releases…"
            message != null -> message
            else -> result?.let {
                when {
                    it.comparison > 0 -> "发现新版本 ${it.tag}"
                    it.comparison == 0 -> "已是最新公开版本 ${it.tag}"
                    else -> "当前版本高于最新公开版本 ${it.tag}"
                }
            }
        }
        // Keep the release page available even when the API is unreachable.
        if (status != null) Text(status, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        SettingsDivider()
        val available = result?.takeIf { it.comparison > 0 }
        SettingRow(if (available == null) "GitHub 发布记录" else "查看新版本 ${available.tag}",
            onClick = { open("$COMPANION_GITHUB/releases/${available?.let { "tag/${it.tag}" } ?: "latest"}") })
    }
    SettingsHeading("鸣谢")
    SettingsGroup {
        SettingRow("Token Monitor", "感谢 Javis603 与贡献者提供桌面采集、Hub 和 API",
            onClick = { open("https://github.com/Javis603/token-monitor") })
        SettingsDivider()
        SettingRow("Token Monitor for Android", "感谢 AoXIUy 提供 Android 客户端参考",
            onClick = { open("https://github.com/AoXIUy/token-monitor-For-Android") })
    }
}

package io.github.jerryskywalker.tmcomp.wear

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.jerryskywalker.tmcomp.core.HubException
import io.github.jerryskywalker.tmcomp.core.WatchHubResult
import io.github.jerryskywalker.tmcomp.core.WatchPairingSession
import io.github.jerryskywalker.tmcomp.core.hubConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WatchImportHub(sync: WatchSync, displayOnly: Boolean, onDismiss: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dismiss by rememberUpdatedState(onDismiss)
    val complete by rememberUpdatedState(onComplete)
    var session by remember { mutableStateOf<WatchPairingSession?>(null) }
    var code by remember { mutableStateOf<ImageBitmap?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session?.close()
                dismiss()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session?.close()
        }
    }

    LaunchedEffect(Unit) {
        var opened: WatchPairingSession? = null
        try {
            val listener = withContext(Dispatchers.IO) {
                WatchPairingSession.open(context).also { opened = it }
            }
            session = listener
            code = withContext(Dispatchers.Default) { pairingQr(listener.code.encode()) }
            val result = listener.receiveTransfer { incoming ->
                withContext(Dispatchers.Main) { checking = true; code = null }
                try {
                    if (displayOnly) {
                        require(incoming.origin == null && incoming.secret == null) { "此配对码用于显示设置" }
                        sync.configureLocalDisplay(requireNotNull(incoming.display) { "手机未发送显示设置" })
                        WatchHubResult(displayApplied = true)
                    } else {
                        val hub = hubConnection(requireNotNull(incoming.origin) { "此配对码用于 Hub" },
                            requireNotNull(incoming.secret) { "手机未发送 Hub Secret" }, BuildConfig.DEBUG)
                        sync.configureLocalHub(hub, fromPhoneQr = true)
                        WatchHubResult(origin = hub.origin)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: HubException) {
                    WatchHubResult(error = failure.message)
                } catch (failure: IllegalArgumentException) {
                    WatchHubResult(error = failure.message)
                } catch (_: Exception) {
                    WatchHubResult(error = "手表无法连接该 Hub，请检查手表网络与 Hub 设置")
                }
            }
            if (result.origin != null || result.displayApplied) complete() else error = result.error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法打开配对码"
        } finally {
            opened?.close()
            code = null
            checking = false
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            val window = (view.parent as? DialogWindowProvider)?.window
            val previousDim = window?.attributes?.dimAmount
            view.keepScreenOn = true
            window?.setDimAmount(.26f)
            onDispose {
                view.keepScreenOn = previous
                if (previousDim != null) window.setDimAmount(previousDim)
            }
        }
        val shape = RoundedCornerShape(23.dp)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth(.82f).background(Color(0xd4233733), shape)
                    .border(1.dp, Color.White.copy(alpha = .22f), shape).padding(horizontal = 8.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val image = code
                if (image != null) {
                    Text("用手机扫码", style = MaterialTheme.typography.caption1)
                    Image(image, contentDescription = "一次性手表配对码", modifier = Modifier.size(132.dp))
                    Text("同一 Wi‑Fi · 2 分钟有效", style = MaterialTheme.typography.caption2)
                } else {
                    val status = when {
                        !checking -> "正在生成配对码"
                        displayOnly -> "正在应用显示设置"
                        else -> "正在验证 Hub"
                    }
                    Text(error ?: status,
                        modifier = Modifier.size(width = 132.dp, height = 58.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption1)
                    if (error != null) Chip(onClick = dismiss, label = { Text("关闭") })
                }
            }
        }
    }
}

private fun pairingQr(content: String): ImageBitmap {
    val size = 320
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { if (bits[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

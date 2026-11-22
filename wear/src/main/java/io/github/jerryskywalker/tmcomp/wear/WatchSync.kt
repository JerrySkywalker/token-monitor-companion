package io.github.jerryskywalker.tmcomp.wear

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.jerryskywalker.tmcomp.core.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class PhoneLink(val node: String, val binding: String, val origin: String)

@Serializable
data class WatchView(
    val config: WatchConfig? = null,
    val snapshot: Snapshot? = null,
    val report: Report? = null,
    val error: String? = null,
)

class WatchSync(context: Context) {
    private val context = context.applicationContext
    private val storage = DeviceStorage(context)
    private val transport = Transport(context)
    private val label get() = Build.MODEL.take(70)
    private val id get() = storage.installationId

    fun view() = storage.read<WatchView>("view") ?: WatchView()
    fun link() = storage.read<PhoneLink>("link")
    fun savedOrigin(): String = storage.credentials()?.origin ?: link()?.origin.orEmpty()
    private fun save(view: WatchView) = storage.write("view", view)

    suspend fun tick(force: Boolean = false) = mutex.withLock { tickLocked(force) }

    private suspend fun tickLocked(force: Boolean) {
        acceptPhoneData()
        val current = view()
        val credentials = storage.credentials()
        if (current.config?.mode == Mode.DIRECT || (current.config == null && credentials != null)) {
            if (credentials == null || (link()?.origin?.let { it != credentials.origin } == true)) {
                save(current.copy(error = null))
                publishStatus(WatchStatus.NEEDS_PAIRING)
                return
            }
            schedule(current.config?.refreshMinutes ?: 30, credentials)
            val api = credentials.gateway()
            try {
                val config = api.watchConfig()
                require(config.id == id) { "服务器返回的设备身份不匹配" }
                if (current.config == null || config.version >= current.config.version) {
                    val retained = current.snapshot.takeIf { current.config?.mode == config.mode }
                    save(current.copy(config = config, snapshot = retained, error = null))
                }
                val updated = view()
                val desired = requireNotNull(updated.config)
                if (desired.mode == Mode.PHONE) {
                    stopDirect()
                    acceptPhoneData()
                    phoneStatus(force)
                    publishHttp(api)
                    return
                }
                schedule(desired.refreshMinutes, credentials)
                if (desired.paused) {
                    publishStatus(WatchStatus.PAUSED, force)
                } else {
                    val snapshot = api.watchUsage()
                    save(updated.copy(snapshot = snapshot, error = null))
                    publishStatus(WatchStatus.READY, force)
                }
                publishHttp(api)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GatewayException) {
                if (failure.status in listOf(401, 404)) {
                    storage.saveCredentials(null)
                    stopDirect()
                    save(view().copy(error = failure.message))
                    publishStatus(WatchStatus.NEEDS_PAIRING)
                } else {
                    save(view().copy(error = failure.message))
                    publishStatus(WatchStatus.OFFLINE)
                }
            } catch (failure: Exception) {
                save(view().copy(error = failure.localizedMessage ?: "连接失败"))
                publishStatus(WatchStatus.OFFLINE)
            }
        } else {
            stopDirect()
            phoneStatus(force)
        }
    }

    private suspend fun phoneStatus(force: Boolean) {
        val current = view()
        val config = current.config
        if (config == null) {
            publishPacket()
            return
        }
        val state = when {
            config.paused -> WatchStatus.PAUSED
            current.snapshot == null -> WatchStatus.WAITING
            else -> WatchStatus.READY
        }
        publishStatus(state, force)
    }

    private suspend fun acceptPhoneData() {
        val previous = link()
        val packet = checked { transport.items(FROM_PHONE) }.orEmpty().asSequence()
            .filter { it.path == FROM_PHONE + id && (previous == null || it.node == previous.node) }
            .mapNotNull { incoming ->
                val value = runCatching { wireJson.decodeFromString<PhonePacket>(incoming.body) }.getOrNull()
                if (value != null && value.config.id == id && validOrigin(value.origin)) incoming.node to value else null
            }
            .filter { (_, value) ->
                if (previous != null) value.binding == previous.binding && value.origin == previous.origin
                else !value.detached && (storage.credentials()?.origin?.let { it == value.origin } ?: true)
            }.maxByOrNull { it.second.config.version } ?: return
        val (node, value) = packet
        if (value.detached) {
            resetLocked()
            publishPacket()
            return
        }
        if (previous == null) {
            storage.write("link", PhoneLink(node, value.binding, value.origin))
        }
        val current = view()
        val config = current.config
        val sameRegistration = previous != null || storage.credentials() != null
        if (sameRegistration && config != null && value.config.version < config.version) return
        val retained = current.snapshot.takeIf { sameRegistration && config?.mode == value.config.mode }
        val provided = value.snapshot?.takeIf {
            value.config.mode == Mode.PHONE && !value.config.paused &&
                (retained == null || it.retrievedAt >= retained.retrievedAt)
        }
        val error = current.error.takeIf { value.config.mode == Mode.DIRECT }
        save(current.copy(config = value.config, snapshot = provided ?: retained, error = error))
    }

    suspend fun enroll(pair: PairCode) = mutex.withLock {
        require(link()?.origin?.let { it == pair.url } ?: true) { "服务器地址与手机绑定不一致" }
        enrollLocked(pair)
    }

    suspend fun receive(node: String, message: DirectInvite) = mutex.withLock {
        acceptPhoneData()
        val current = link() ?: return@withLock
        if (message.id != id || message.binding != current.binding || message.pair.url != current.origin ||
            current.node != node || !validOrigin(message.pair.url)) return@withLock
        if (message.pair.expires != 0L && message.pair.expires <= System.currentTimeMillis()) return@withLock
        val credentials = storage.credentials()
        if (credentials?.origin == message.pair.url &&
            checked { credentials.gateway().watchConfig() }?.id == id) {
            tickLocked(false)
        } else {
            enrollLocked(message.pair)
        }
    }

    private suspend fun enrollLocked(pair: PairCode) {
        val credentials = Gateway(pair.url).enroll(pair.code, "wear", label, id)
        storage.saveCredentials(credentials)
        val old = view()
        if (link() == null) save(WatchView(report = old.report))
        tickLocked(true)
    }

    suspend fun reset() = mutex.withLock {
        resetLocked()
        publishPacket()
    }

    private suspend fun resetLocked() {
        val unlinkedId = id
        storage.saveCredentials(null)
        storage.write<PhoneLink>("link", null)
        storage.write<WatchView>("view", null)
        storage.preferences.edit().remove("installation").remove("report_sequence").commit()
        stopDirect()
        checked { transport.removeOwned(FROM_WATCH + unlinkedId) }
    }

    private suspend fun publishStatus(status: WatchStatus, force: Boolean = false) {
        val current = view()
        val config = current.config ?: return
        val at = current.snapshot?.upstreamAt
        val prior = current.report
        if (force || prior == null || prior.appliedVersion != config.version || prior.mode != config.mode || prior.state != status || prior.snapshotAt != at) {
            val sequence = maxOf(storage.preferences.getLong("report_sequence", 0), prior?.sequence ?: 0) + 1
            storage.preferences.edit().putLong("report_sequence", sequence).apply()
            save(current.copy(report = Report(config.version, sequence, config.mode, status, at)))
        }
        publishPacket()
    }

    private suspend fun publishPacket() {
        val view = view()
        checked { transport.send(FROM_WATCH + id, wireJson.encodeToString(WatchPacket(id, label, link()?.binding, view.report))) }
    }

    private suspend fun publishHttp(api: Gateway) {
        val report = view().report ?: return
        checked { api.watchReport(report) }
    }

    private fun validOrigin(value: String) = runCatching { origin(value, BuildConfig.DEBUG) == value }.getOrDefault(false)

    private fun schedule(minutes: Long, credentials: Credentials) {
        val key = minutes.coerceIn(15, 1440).toString() + "|" + credentials.origin
        val same = storage.preferences.getString("schedule", null) == key
        val periodic = PeriodicWorkRequestBuilder<WatchWorker>(minutes.coerceIn(15, 1440), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, if (same) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE, periodic)
        if (!same) storage.preferences.edit().putString("schedule", key).apply()
    }

    private fun stopDirect() {
        if (storage.preferences.contains("schedule")) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            storage.preferences.edit().remove("schedule").apply()
        }
    }

    companion object {
        private const val PERIODIC = "watch-direct"
        private val mutex = Mutex()

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("wear-phone-data", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WatchWorker>().build())
        }
    }
}

private suspend inline fun <T> checked(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

class WatchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        WatchSync(applicationContext).tick()
        return Result.success()
    }
}

class WatchListener : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        WatchSync.enqueue(this)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != ENROLL) return
        val message = runCatching { wireJson.decodeFromString<DirectInvite>(messageEvent.data.decodeToString()) }.getOrNull() ?: return
        val node = messageEvent.sourceNodeId
        val context = applicationContext
        scope.launch { checked { WatchSync(context).receive(node, message) } }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

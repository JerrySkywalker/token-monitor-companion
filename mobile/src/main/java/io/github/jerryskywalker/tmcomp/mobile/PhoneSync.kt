package io.github.jerryskywalker.tmcomp.mobile

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.github.jerryskywalker.tmcomp.core.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

internal const val PHONE_VIEW_KEY = "hub_phone_view"
private const val LINK_PREFIX = "hub_link_"

@Serializable
data class WatchLink(val binding: String, val node: String)

@Serializable
data class PhoneState(
    val snapshot: Snapshot? = null,
    val watches: List<WatchConfig> = emptyList(),
    val available: List<DiscoveredWatch> = emptyList(),
    val connected: List<String> = emptyList(),
    val usageError: String? = null,
    val wearAvailable: Boolean? = null,
)

@Serializable
data class DiscoveredWatch(val id: String, val label: String, val node: String, val initial: WatchConfig? = null)

class PhoneSync(context: Context) {
    private val context = context.applicationContext
    val storage = DeviceStorage(context)
    private val transport = Transport(context)
    fun state() = storage.read<PhoneState>(PHONE_VIEW_KEY) ?: PhoneState()
    private fun link(id: String) = storage.read<WatchLink>(LINK_PREFIX + id)

    private suspend fun save(value: PhoneState) {
        val previous = state()
        if (previous == value) return
        storage.write(PHONE_VIEW_KEY, value)
        if (previous.snapshot != value.snapshot || previous.usageError != value.usageError) {
            attempt { DashboardWidget().updateAll(context) }
        }
    }

    suspend fun connect(credentials: HubConnection) = mutex.withLock {
        val previous = state()
        val old = storage.credentials()
        val snapshot = Hub(credentials).snapshot(previous.snapshot.takeIf {
            old?.origin == credentials.origin && old.secret == credentials.secret
        }, extendedTrends = true)
        storage.saveCredentials(credentials)
        save(previous.copy(snapshot = snapshot, usageError = null))
        schedule(context)
        syncWatches()
    }

    suspend fun refresh() = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked() {
        val credentials = storage.credentials()
        val previous = state()
        val usage = credentials?.let { attempt { Hub(it).snapshot(previous.snapshot, extendedTrends = true) } }
        if (usage != null) {
            save(previous.copy(snapshot = usage.getOrNull() ?: previous.snapshot,
                usageError = usage.exceptionOrNull()?.let { it.message ?: "Hub 连接暂不可用" }))
        }
        syncWatches()
        usage?.getOrThrow()
    }

    suspend fun discover() = mutex.withLock { syncWatches() }

    private suspend fun syncWatches() {
        val previous = state()
        val peers = attempt { transport.connectedNodes() }
        val inbox = attempt { transport.items(FROM_WATCH) }
        val nodes = peers.getOrDefault(emptySet())
        val packets = inbox.getOrDefault(emptyList()).mapNotNull { item ->
            val message = runCatching { wireJson.decodeFromString<WatchPacket>(item.body) }.getOrNull() ?: return@mapNotNull null
            if (item.path != FROM_WATCH + message.id || !idPattern.matches(message.id)) return@mapNotNull null
            item.node to message
        }
        if (BuildConfig.DEBUG) Log.d("TmCompDataLayer",
            "Connected nodes=${nodes.size}, watch items=${packets.size}, " +
                "node error=${dataLayerError(peers.exceptionOrNull())}, inbox error=${dataLayerError(inbox.exceptionOrNull())}")
        val watches = previous.watches.map { watch ->
            val link = link(watch.id) ?: return@map watch
            val latest = packets.asSequence().filter { (node, packet) ->
                node == link.node && packet.id == watch.id && link.binding == packet.binding
            }.mapNotNull { it.second.report }.filter {
                it.sequence > (watch.report?.sequence ?: 0L) && it.appliedVersion <= watch.version
            }.maxByOrNull { it.sequence }
            watch.copy(report = latest ?: watch.report,
                ringProvider = if (latest?.appliedVersion == watch.version) latest.ringProvider else watch.ringProvider)
        }
        val available = packets.filter { (node, packet) -> node in nodes && packet.binding == null && link(packet.id) == null }
            .map { (node, packet) -> DiscoveredWatch(packet.id, packet.label.take(70), node, packet.initial) }.distinctBy { it.id }
        val delivered = previous.snapshot?.let { if (previous.usageError == null) it else it.copy(stale = true) }
        save(previous.copy(watches = watches, available = available,
            connected = watches.filter { link(it.id)?.node in nodes }.map { it.id },
            wearAvailable = peers.isSuccess && inbox.isSuccess))
        for (watch in watches) {
            val connection = link(watch.id) ?: continue
            attempt { send(watch, connection, delivered) }
        }
    }

    suspend fun bind(device: DiscoveredWatch) = mutex.withLock {
        require(device.node in transport.connectedNodes()) { "请让手表保持连接并打开应用" }
        val previous = state()
        if (previous.watches.any { it.id == device.id }) return@withLock
        val initial = device.initial
        val watch = WatchConfig(device.id, device.label, mode = initial?.mode ?: Mode.PHONE,
            pinnedId = initial?.pinnedId, paused = initial?.paused ?: false,
            refreshMinutes = initial?.refreshMinutes?.coerceIn(15, 1440) ?: 30,
            ringProvider = initial?.ringProvider)
        val connection = WatchLink(UUID.randomUUID().toString(), device.node)
        storage.write(LINK_PREFIX + device.id, connection)
        save(previous.copy(watches = previous.watches + watch,
            available = previous.available.filterNot { it.id == device.id }, connected = previous.connected + device.id))
        send(watch, connection, previous.snapshot?.let { if (previous.usageError == null) it else it.copy(stale = true) })
    }

    suspend fun edit(watch: WatchConfig, desired: WatchConfig) = mutex.withLock {
        val current = state()
        val active = current.watches.firstOrNull { it.id == watch.id } ?: error("手表已移除")
        require(active.version == watch.version) { "手表设置已更新，请重试" }
        val connection = requireNotNull(link(watch.id)) { "请先绑定手表" }
        val updated = desired.copy(version = active.version + 1, report = active.report)
        save(current.copy(watches = current.watches.map { if (it.id == watch.id) updated else it }))
        if (updated.mode == Mode.PHONE && !updated.paused && updated.refreshRequest != active.refreshRequest) {
            refreshLocked()
        } else {
            val snapshot = current.snapshot?.let { if (current.usageError == null) it else it.copy(stale = true) }
            send(updated, connection, snapshot)
        }
    }

    suspend fun configureWatchHub(watch: WatchConfig, credentials: HubConnection) = mutex.withLock {
        val connection = requireNotNull(link(watch.id)) { "请先绑定手表" }
        require(connection.node in transport.connectedNodes()) { "等待手表连接后再设置 Hub" }
        val reply = transport.configureHub(connection.node, WatchHubSetup(watch.id, connection.binding, credentials))
        require(reply.origin == credentials.origin) { reply.error ?: "手表未确认 Hub 配置" }
        syncWatches()
    }

    suspend fun configureWatchWithPhoneHub(watch: WatchConfig) {
        configureWatchHub(watch, requireNotNull(storage.credentials()) { "请先配置手机的 Hub" })
    }

    suspend fun remove(watch: WatchConfig) = mutex.withLock {
        link(watch.id)?.let { send(watch, it, null, detached = true) }
        storage.write<WatchLink>(LINK_PREFIX + watch.id, null)
        val local = state()
        save(local.copy(watches = local.watches.filterNot { it.id == watch.id },
            available = local.available.filterNot { it.id == watch.id }, connected = local.connected - watch.id))
    }

    suspend fun clear() = mutex.withLock {
        storage.saveCredentials(null)
        save(state().copy(snapshot = null, usageError = null))
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        syncWatches()
    }

    private suspend fun send(watch: WatchConfig, link: WatchLink, snapshot: Snapshot?, detached: Boolean = false) {
        val payload = PhonePacket(link.binding, storage.credentials()?.origin.orEmpty(), watch.copy(report = null),
            snapshot?.takeIf { !detached && watch.mode == Mode.PHONE && !watch.paused }?.forWatch(watch.pinnedId), detached)
        transport.send(FROM_PHONE + watch.id, wireJson.encodeToString(payload))
    }

    companion object {
        private const val PERIODIC_NAME = "phone-usage"
        private val mutex = Mutex()
        private val idPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PhoneWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PhoneWearWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("phone-wear-event", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

private fun dataLayerError(error: Throwable?) =
    if (error is ApiException) error.message?.take(240) ?: "ApiException(${error.statusCode})"
    else error?.javaClass?.simpleName

private suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

class PhoneWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val error = attempt { PhoneSync(applicationContext).refresh() }.exceptionOrNull()
        return if (error == null || (error is HubException && error.status in 400..499)) Result.success()
        else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

class PhoneWearWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        PhoneSync(applicationContext).discover()
        return Result.success()
    }
}

class PhoneListener : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        PhoneSync.enqueue(this)
    }
}

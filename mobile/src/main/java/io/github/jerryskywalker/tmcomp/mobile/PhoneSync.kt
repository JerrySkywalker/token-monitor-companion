package io.github.jerryskywalker.tmcomp.mobile

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
import com.google.android.gms.wearable.WearableListenerService
import io.github.jerryskywalker.tmcomp.core.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class WatchLink(val binding: String, val node: String)

@Serializable
data class PhoneState(
    val snapshot: Snapshot? = null,
    val watches: List<WatchConfig> = emptyList(),
    val available: List<DiscoveredWatch> = emptyList(),
    val connected: List<String> = emptyList(),
    val usageError: String? = null,
)

@Serializable
data class DiscoveredWatch(val id: String, val label: String, val node: String)

class PhoneSync(context: Context) {
    private val context = context.applicationContext
    val storage = DeviceStorage(context)
    private val transport = Transport(context)
    fun state() = storage.read<PhoneState>("view") ?: PhoneState()
    fun link(id: String) = storage.read<WatchLink>("link_" + id)
    private fun save(value: PhoneState) = storage.write("view", value)
    private fun creds() = requireNotNull(storage.credentials()) { "请先配对服务器" }

    suspend fun enroll(pair: PairCode) = mutex.withLock {
        val previous = storage.credentials()?.origin
        val credentials = Gateway(pair.url).enroll(pair.code, "phone", Build.MODEL.take(70))
        if (previous != credentials.origin) clearLinks()
        storage.saveCredentials(credentials)
        save(PhoneState())
        schedule(context)
        refreshLocked(credentials)
    }

    suspend fun refresh() = mutex.withLock {
        val credentials = storage.credentials() ?: return@withLock
        refreshLocked(credentials)
    }

    private suspend fun refreshLocked(credentials: Credentials) {
        val api = credentials.gateway()
        val previous = state()
        val nodes = attempt { transport.connectedNodes() }.getOrDefault(emptySet())
        val packets = attempt { transport.items(FROM_WATCH) }.getOrDefault(emptyList()).mapNotNull { item ->
            val message = runCatching { wireJson.decodeFromString<WatchPacket>(item.body) }.getOrNull() ?: return@mapNotNull null
            if (item.path != FROM_WATCH + message.id || !idPattern.matches(message.id)) return@mapNotNull null
            item.node to message
        }
        val knownWatches = api.watches()
        val reports = knownWatches.associate { it.id to (it.report?.sequence ?: 0L) }
        val watched = knownWatches.associateBy { it.id }.toMutableMap()
        for ((node, message) in packets) {
            val link = link(message.id) ?: continue
            val report = message.report ?: continue
            if (node != link.node || message.binding != link.binding || reports[message.id] == null) continue
            if (report.sequence > reports.getValue(message.id)) {
                watched[message.id] = api.relayReport(message.id, report)
            }
        }
        val usage = attempt { api.usage() }
        if (usage.exceptionOrNull().let { it is GatewayException && it.status == 401 }) throw usage.exceptionOrNull()!!
        val snapshot = usage.getOrNull() ?: previous.snapshot
        val delivered = if (usage.isSuccess) snapshot else snapshot?.copy(stale = true)
        val available = packets.filter { (node, packet) -> node in nodes && link(packet.id) == null }
            .map { (node, packet) -> DiscoveredWatch(packet.id, packet.label.take(70), node) }.distinctBy { it.id }
        val watches = watched.values.toList()
        save(PhoneState(snapshot, watches, available, watches.filter { link(it.id)?.node in nodes }.map { it.id },
            usage.exceptionOrNull()?.message))
        for (watch in watches) {
            val link = link(watch.id) ?: continue
            attempt { send(watch, link, credentials.origin, delivered) }
        }
    }

    suspend fun bind(device: DiscoveredWatch) = mutex.withLock {
        require(device.node in transport.connectedNodes()) { "请让手表保持连接并打开应用" }
        val credentials = creds()
        val watch = credentials.gateway().register(device.id, device.label)
        val link = WatchLink(UUID.randomUUID().toString(), device.node)
        storage.write("link_" + device.id, link)
        send(watch, link, credentials.origin, state().snapshot)
        refreshLocked(credentials)
    }

    suspend fun edit(watch: WatchConfig, desired: WatchConfig): PairCode? = mutex.withLock {
        val credentials = creds()
        val api = credentials.gateway()
        val updated = try {
            api.update(watch.id, desired.edit(watch))
        } catch (error: GatewayException) {
            if (error.status == 409) refreshLocked(credentials)
            throw error
        }
        val local = state()
        var snapshot = local.snapshot
        var usageError = local.usageError
        if (updated.mode == Mode.PHONE && !updated.paused && updated.refreshRequest != watch.refreshRequest) {
            val current = attempt { api.usage() }
            snapshot = current.getOrNull() ?: snapshot?.copy(stale = true)
            usageError = current.exceptionOrNull()?.message
        }
        save(local.copy(snapshot = snapshot, usageError = usageError,
            watches = local.watches.map { if (it.id == updated.id) updated else it }))
        val link = link(watch.id)
        if (link != null) attempt { send(updated, link, credentials.origin, snapshot) }
        if (updated.mode == Mode.DIRECT && watch.mode == Mode.PHONE) issueInvite(updated, credentials) else null
    }

    suspend fun invite(watch: WatchConfig): PairCode = mutex.withLock { issueInvite(watch, creds()) }

    private suspend fun issueInvite(watch: WatchConfig, credentials: Credentials): PairCode {
        val pair = credentials.gateway().invite(watch.id).copy(url = credentials.origin)
        val link = link(watch.id)
        if (link != null) attempt { transport.sendInvite(link.node, DirectInvite(watch.id, link.binding, pair)) }
        return pair
    }

    suspend fun remove(watch: WatchConfig) = mutex.withLock {
        val credentials = creds()
        credentials.gateway().remove(watch.id)
        link(watch.id)?.let {
            attempt { send(watch, it, credentials.origin, null, detached = true) }
        }
        storage.write<WatchLink>("link_" + watch.id, null)
        val local = state()
        save(local.copy(watches = local.watches.filterNot { it.id == watch.id }, available = local.available.filterNot { it.id == watch.id }))
    }

    suspend fun clear() = mutex.withLock {
        storage.saveCredentials(null)
        clearLinks()
        save(PhoneState())
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
    }

    private fun clearLinks() {
        val editor = storage.preferences.edit()
        storage.preferences.all.keys.filter { it.startsWith("link_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    private suspend fun send(watch: WatchConfig, link: WatchLink, origin: String, snapshot: Snapshot?, detached: Boolean = false) {
        val payload = PhonePacket(
            binding = link.binding,
            origin = origin,
            config = watch.copy(report = null),
            snapshot = snapshot?.takeIf { !detached && watch.mode == Mode.PHONE && !watch.paused }?.forWatch(watch.pinnedId),
            detached = detached,
        )
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
            val request = OneTimeWorkRequestBuilder<PhoneWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork("phone-wear-event", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

private suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

class PhoneWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val outcome = attempt { PhoneSync(applicationContext).refresh() }
        val error = outcome.exceptionOrNull()
        return if (error == null || (error is GatewayException && error.status in 400..499)) Result.success()
        else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

class PhoneListener : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        PhoneSync.enqueue(this)
    }
}

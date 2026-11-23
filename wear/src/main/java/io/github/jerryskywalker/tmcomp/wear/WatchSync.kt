package io.github.jerryskywalker.tmcomp.wear

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.github.jerryskywalker.tmcomp.core.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

internal const val WATCH_VIEW = "hub_watch_view"

@Serializable
data class PhoneLink(val node: String, val binding: String)

@Serializable
data class WatchView(
    val config: WatchConfig? = null,
    val snapshot: Snapshot? = null,
    val report: Report? = null,
    val error: String? = null,
    val phoneOrigin: String? = null,
    val localOnly: Boolean = false,
)

class WatchSync(context: Context) {
    private val context = context.applicationContext
    private val storage = DeviceStorage(context)
    private val transport by lazy { Transport(context) }
    private val label get() = Build.MODEL.take(70)
    private val id get() = storage.installationId

    fun view() = storage.read<WatchView>(WATCH_VIEW) ?: WatchView()
    fun changes() = storage.changes(WATCH_VIEW).map { view() }
    fun hubChanges() = storage.hubChanges()
    fun phoneUpdates() = transport.updates(FROM_PHONE).catch { }
    fun link() = storage.read<PhoneLink>(LINK)
    fun hubOrigin() = storage.credentials()?.origin.orEmpty()
    suspend fun phoneConnected(): Boolean? = link()?.let { it.node in checked { transport.connectedNodes() }.orEmpty() }
    private fun save(view: WatchView) {
        val previous = view()
        if (view == previous) return
        storage.write(WATCH_VIEW, view)
        if (view.snapshot != previous.snapshot || view.config != previous.config || view.error != previous.error) {
            runCatching { TileService.getUpdater(context).requestUpdate(DashboardTile::class.java) }
        }
    }

    suspend fun tick(force: Boolean = false) = mutex.withLock { tickLocked(force) }

    suspend fun receivePhoneUpdate() = mutex.withLock { tickLocked(force = false, fetchDirect = false) }

    private suspend fun tickLocked(force: Boolean, fetchDirect: Boolean = true) {
        val previous = view().config
        acceptPhoneData()
        val current = view()
        val config = current.config
        val credentials = storage.credentials()
        when {
            config == null -> {
                stopDirect()
                publishPacket()
            }
            config.paused -> {
                stopDirect()
                publishStatus(WatchStatus.PAUSED, force)
            }
            config.mode == Mode.PHONE -> {
                stopDirect()
                publishStatus(if (current.snapshot == null) WatchStatus.WAITING else WatchStatus.READY, force)
            }
            credentials == null -> {
                stopDirect()
                publishStatus(WatchStatus.NEEDS_PAIRING, force)
            }
            else -> {
                schedule(config.refreshMinutes)
                val requested = previous?.mode != Mode.DIRECT || previous.paused || previous.refreshRequest != config.refreshRequest
                if (!fetchDirect && !requested && current.snapshot != null) {
                    publishStatus(if (current.error == null) WatchStatus.READY else WatchStatus.OFFLINE)
                    return
                }
                try {
                    val snapshot = Hub(credentials).snapshot(current.snapshot, extendedTrends = true)
                        .forWatch(config.pinnedId, keepTrends = true)
                    save(current.copy(snapshot = snapshot, error = null))
                    publishStatus(WatchStatus.READY, force)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    save(current.copy(error = failure.localizedMessage ?: "Hub 连接失败"))
                    publishStatus(WatchStatus.OFFLINE, force)
                }
            }
        }
    }

    private suspend fun acceptPhoneData() {
        if (view().localOnly) return
        val previous = link()
        val inbox = checked { transport.items(FROM_PHONE) }.orEmpty()
        val packet = inbox.asSequence()
            .filter { it.path == FROM_PHONE + id && (previous == null || it.node == previous.node) }
            .mapNotNull { item ->
                val value = runCatching { wireJson.decodeFromString<PhonePacket>(item.body) }.getOrNull()
                if (value?.config?.id == id) item.node to value else null
            }.filter { (_, value) ->
                if (previous != null) value.binding == previous.binding else !value.detached
            }.maxByOrNull { it.second.config.version } ?: return
        val (node, value) = packet
        if (value.detached) {
            resetLocked()
            publishPacket()
            return
        }
        val current = view()
        val config = if (previous != null && current.config?.version == value.config.version) {
            value.config.copy(report = null, ringProvider = current.config.ringProvider)
        } else value.config.copy(report = null)
        if (previous != null && current.config != null && config.version < current.config.version) return
        if (previous == null) storage.write(LINK, PhoneLink(node, value.binding))
        val modeMatches = current.config?.mode == config.mode
        val sameSource = config.mode == Mode.DIRECT || current.phoneOrigin == value.origin
        val retained = current.snapshot.takeIf { modeMatches && sameSource }
        val provided = value.snapshot?.takeIf {
            config.mode == Mode.PHONE && !config.paused &&
                (retained == null || newerThanOrEqual(it.retrievedAt, retained.retrievedAt))
        }
        val base = provided ?: retained
        val trends = if (base != null && config.mode == Mode.PHONE && !config.paused) inbox
            .firstOrNull { it.node == node && it.path == PHONE_TRENDS + id }
            ?.let { runCatching { wireJson.decodeFromString<WatchTrendPacket>(it.body) }.getOrNull() }
            ?.takeIf { it.binding == value.binding && it.retrievedAt == base.retrievedAt } else null
        val snapshot = when {
            base != null && trends != null -> base.copy(
                history = trends.history.takeLast(366), trendSamples = trends.samples.takeLast(325))
            provided != null && retained != null -> {
                // Data Layer can deliver the usage and trend items in separate callbacks.
                val history = (retained.history + provided.history).associateBy { it.date }
                provided.copy(history = history.values.sortedBy { it.date }.takeLast(366), trendSamples = retained.trendSamples)
            }
            else -> base
        }
        save(current.copy(config = config, snapshot = snapshot, phoneOrigin = value.origin,
            error = current.error.takeIf { config.mode == Mode.DIRECT && modeMatches }))
    }

    private fun newerThanOrEqual(next: String, prior: String): Boolean =
        runCatching { !Instant.parse(next).isBefore(Instant.parse(prior)) }.getOrDefault(false)

    suspend fun configureHub(node: String, setup: WatchHubSetup): WatchHubResult = mutex.withLock {
        acceptPhoneData()
        val connection = link()
        if (connection == null || setup.id != id || setup.binding != connection.binding || connection.node != node) {
            return@withLock WatchHubResult(error = "手表未与这台手机绑定")
        }
        val hub = hubConnection(setup.hub.origin, setup.hub.secret, BuildConfig.DEBUG)
        val current = view()
        val config = requireNotNull(current.config) { "请先在手机上完成手表绑定" }
        saveHub(hub, current, config)
        WatchHubResult(origin = hub.origin)
    }

    suspend fun configureLocalHub(hub: HubConnection, fromPhoneQr: Boolean = false) = mutex.withLock {
        val current = view()
        val bound = link() != null
        val wasPhoneMode = current.config?.mode == Mode.PHONE
        require(!bound || !wasPhoneMode || fromPhoneQr) { "请先切换到独立直连" }
        val config = (current.config ?: WatchConfig(id, label)).copy(mode = Mode.DIRECT)
        // QR is also available if the existing Data Layer link is temporarily unavailable.
        val localOnly = current.localOnly || !bound || (fromPhoneQr && wasPhoneMode)
        saveHub(hub, current.copy(localOnly = localOnly), config)
    }

    suspend fun enablePhoneDiscovery() = mutex.withLock {
        save(view().copy(localOnly = false))
        tickLocked(force = true)
    }

    suspend fun configureLocalDisplay(display: WatchDisplay) = mutex.withLock {
        val current = view()
        val config = requireNotNull(current.config) { "请先配置 Hub" }
        require(link() == null || current.localOnly) { "请在手机已绑定手表的设置中保存" }
        val clean = display.copy(quotaOrders = display.quotaOrders.mapValues { (_, ids) ->
            ids.distinct().take(80).filter { it.length <= 100 }
        })
        save(current.copy(config = config.copy(display = clean)))
    }

    private suspend fun saveHub(hub: HubConnection, current: WatchView, config: WatchConfig) {
        val oldHub = storage.credentials()
        val snapshot = Hub(hub).snapshot(current.snapshot.takeIf {
            config.mode == Mode.DIRECT && oldHub?.origin == hub.origin && oldHub.secret == hub.secret
        }, extendedTrends = true)
        storage.saveCredentials(hub)
        val direct = config.mode == Mode.DIRECT
        save(current.copy(config = config, snapshot = if (direct) snapshot.forWatch(config.pinnedId, keepTrends = true) else current.snapshot,
            error = null))
        if (direct && !config.paused) schedule(config.refreshMinutes)
        val status = when {
            config.paused -> WatchStatus.PAUSED
            view().snapshot == null -> WatchStatus.WAITING
            else -> WatchStatus.READY
        }
        publishStatus(status, true)
    }

    suspend fun changeRing(provider: String?) = mutex.withLock {
        val current = view()
        val config = current.config ?: return@withLock
        save(current.copy(config = config.copy(ringProvider = provider)))
        publishStatus(current.report?.state ?: WatchStatus.WAITING, force = true)
    }

    suspend fun reset() = mutex.withLock {
        resetLocked()
        publishPacket()
    }

    private suspend fun resetLocked() {
        val unlinkedId = id
        storage.saveCredentials(null)
        storage.write<PhoneLink>(LINK, null)
        storage.write<WatchView>(WATCH_VIEW, null)
        storage.preferences.edit().remove("installation").remove(REPORT_SEQUENCE).commit()
        stopDirect()
        checked { transport.removeOwned(FROM_WATCH + unlinkedId) }
    }

    private suspend fun publishStatus(status: WatchStatus, force: Boolean = false) {
        val current = view()
        val config = current.config ?: return
        val at = current.snapshot?.upstreamAt
        val source = storage.credentials()?.origin
        val prior = current.report
        if (force || prior == null || prior.appliedVersion != config.version || prior.mode != config.mode ||
            prior.state != status || prior.snapshotAt != at || prior.hubOrigin != source || prior.ringProvider != config.ringProvider) {
            val sequence = maxOf(storage.preferences.getLong(REPORT_SEQUENCE, 0), prior?.sequence ?: 0) + 1
            storage.preferences.edit().putLong(REPORT_SEQUENCE, sequence).apply()
            val readings = if (config.mode == Mode.DIRECT) current.snapshot?.readings.orEmpty().take(76) else emptyList()
            save(current.copy(report = Report(config.version, sequence, config.mode, status, at, source, readings, config.ringProvider)))
        }
        publishPacket()
    }

    private suspend fun publishPacket() {
        val view = view()
        if (view.localOnly) return
        val binding = link()?.binding
        val packet = WatchPacket(id, label, binding, view.report.takeIf { binding != null },
            view.config?.copy(report = null).takeIf { binding == null })
        try {
            transport.send(FROM_WATCH + id, wireJson.encodeToString(packet))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) Log.w("TmCompDataLayer", "Could not publish watch state", failure)
        }
    }

    private fun schedule(minutes: Long) {
        val interval = minutes.coerceIn(15, 1440)
        val old = storage.preferences.getLong(SCHEDULE, 0L)
        if (old == interval) return
        val periodic = PeriodicWorkRequestBuilder<WatchWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        storage.preferences.edit().putLong(SCHEDULE, interval).apply()
    }

    private fun stopDirect() {
        if (storage.preferences.contains(SCHEDULE) || storage.preferences.contains("schedule")) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            storage.preferences.edit().remove(SCHEDULE).remove("schedule").apply()
        }
    }

    companion object {
        private const val PERIODIC = "watch-direct"
        private const val LINK = "hub_phone_link"
        private const val SCHEDULE = "hub_schedule"
        private const val REPORT_SEQUENCE = "hub_report_sequence"
        private val mutex = Mutex()

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("wear-phone-data", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WatchPhoneWorker>().build())
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

class WatchPhoneWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        WatchSync(applicationContext).receivePhoneUpdate()
        return Result.success()
    }
}

class WatchListener : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        WatchSync.enqueue(this)
    }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != SET_WATCH_HUB || request.size > 8192) return null
        val task = TaskCompletionSource<ByteArray>()
        val context = applicationContext
        val message = runCatching { wireJson.decodeFromString<WatchHubSetup>(request.decodeToString()) }.getOrNull() ?: return null
        scope.launch {
            val reply = try {
                WatchSync(context).configureHub(nodeId, message)
            } catch (cancelled: CancellationException) {
                task.trySetException(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                WatchHubResult(error = failure.localizedMessage ?: "无法设置手表 Hub")
            }
            task.trySetResult(wireJson.encodeToString(reply).encodeToByteArray())
        }
        return task.task
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

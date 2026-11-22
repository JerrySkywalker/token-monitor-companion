package io.github.jerryskywalker.tmcomp.core

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.IOException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

const val FROM_PHONE = "/tmcomp/v1/phone/"
const val FROM_WATCH = "/tmcomp/v1/watch/"
const val ENROLL = "/tmcomp/v1/enroll"
private const val PAYLOAD = "body"

data class Incoming(val node: String, val path: String, val body: String)

fun DataItem.incoming(): Incoming? = runCatching {
    Incoming(requireNotNull(uri.host), requireNotNull(uri.path), requireNotNull(DataMapItem.fromDataItem(this).dataMap.getString(PAYLOAD)))
}.getOrNull()

class Transport(context: Context) {
    private val context = context.applicationContext
    private val data = Wearable.getDataClient(this.context)

    suspend fun send(path: String, payload: String) {
        require(payload.encodeToByteArray().size < 90_000) { "手表快照过大" }
        val request = PutDataMapRequest.create(path)
        request.dataMap.putString(PAYLOAD, payload)
        withTimeoutOrNull(4_000) { data.putDataItem(request.asPutDataRequest().setUrgent()).await() }
            ?: throw IOException("手表连接暂不可用")
    }

    suspend fun items(prefix: String): List<Incoming> = withTimeoutOrNull(4_000) {
        val buffer = data.dataItems.await()
        try {
            buffer.filter { it.uri.path?.startsWith(prefix) == true }.mapNotNull { it.incoming() }
        } finally {
            buffer.release()
        }
    }.orEmpty()

    suspend fun removeOwned(path: String) {
        withTimeoutOrNull(4_000) {
            val node = Wearable.getNodeClient(context).localNode.await()
            val uri = Uri.Builder().scheme("wear").authority(node.id).path(path).build()
            data.deleteDataItems(uri).await()
        }
    }

    suspend fun sendInvite(node: String, invite: DirectInvite) {
        withTimeoutOrNull(4_000) {
            Wearable.getMessageClient(context).sendMessage(
                node, ENROLL, wireJson.encodeToString(DirectInvite.serializer(), invite).encodeToByteArray(),
            ).await()
        } ?: throw IOException("暂时无法向手表发送配对码")
    }

    suspend fun connectedNodes(): Set<String> = withTimeoutOrNull(4_000) {
        Wearable.getNodeClient(context).connectedNodes.await().map { it.id }.toSet()
    }.orEmpty()
}

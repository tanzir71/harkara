package io.github.tanzir71.harkara.tools.lan

import io.github.tanzir71.harkara.core.tools.LocalSendDevice
import io.github.tanzir71.harkara.core.tools.LocalSendFile
import io.github.tanzir71.harkara.core.tools.LocalSendLimits
import io.github.tanzir71.harkara.core.tools.LocalSendPrepare
import io.github.tanzir71.harkara.core.tools.LocalSendPrepared
import io.github.tanzir71.harkara.core.tools.LocalSendServer
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Explicit numeric LAN target: no public URLs, DNS rebinding, redirects, or system HTTP proxy. */
data class LanToolPeer(val address: InetAddress, val port: Int) {
    init {
        val ula = address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc
        require(!address.isAnyLocalAddress && !address.isMulticastAddress &&
            (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || ula))
        require(port in 1..65535)
    }

    fun url(path: String): HttpUrl = HttpUrl.Builder().scheme("http").host(address.hostAddress!!).port(port).encodedPath(path).build()
}

data class OutgoingLanFile(val metadata: LocalSendFile, val open: () -> InputStream)
data class LocalSendResult(val sent: Set<String>, val declined: Set<String>)

/** Blocking IO API: call from Dispatchers.IO, never the main thread. No discovery/listener on creation. */
class LocalSendClient(client: OkHttpClient = OkHttpClient()) : AutoCloseable {
    private val http = client.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(2, TimeUnit.MINUTES).build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false

    fun info(peer: LanToolPeer): LocalSendDevice = LocalSendServer.wireJson.decodeFromString(
        execute(Request.Builder().url(peer.url("${LocalSendServer.API}/info")).build()).decodeToString(),
    )

    /** Remote consent determines the subset sent. Source streams are opened only after that consent. */
    fun send(peer: LanToolPeer, self: LocalSendDevice, files: List<OutgoingLanFile>): LocalSendResult {
        require(files.map { it.metadata.id }.distinct().size == files.size)
        val byId = files.associateBy { it.metadata.id }
        LocalSendLimits().validate(byId.mapValues { it.value.metadata })
        val offer = LocalSendPrepare(self, byId.mapValues { it.value.metadata })
        val prepared = LocalSendServer.wireJson.decodeFromString<LocalSendPrepared>(execute(
            Request.Builder().url(peer.url("${LocalSendServer.API}/prepare-upload"))
                .post(LocalSendServer.wireJson.encodeToString(offer).toRequestBody(JSON)).build(),
        ).decodeToString())
        if (prepared.sessionId.length !in 1..128 || prepared.files.isEmpty() || !byId.keys.containsAll(prepared.files.keys) ||
            prepared.files.values.any { it.length !in 1..128 }) throw IOException("Invalid LocalSend consent response")
        try {
            prepared.files.forEach { (id, token) ->
                val file = byId.getValue(id)
                val url = peer.url("${LocalSendServer.API}/upload").newBuilder()
                    .addQueryParameter("sessionId", prepared.sessionId).addQueryParameter("fileId", id).addQueryParameter("token", token).build()
                val body = object : RequestBody() {
                    override fun contentType() = BINARY
                    override fun contentLength() = file.metadata.size
                    override fun writeTo(sink: BufferedSink) {
                        file.open().use { input ->
                            var remaining = contentLength()
                            val buffer = ByteArray(16 * 1024)
                            while (remaining > 0) {
                                if (closed) throw IOException("File drop stopped")
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (n < 0) throw IOException("File shorter than offered")
                                sink.write(buffer, 0, n)
                                remaining -= n
                            }
                            if (input.read() != -1) throw IOException("File longer than offered")
                        }
                    }
                }
                execute(Request.Builder().url(url).post(body).build())
            }
            return LocalSendResult(prepared.files.keys, byId.keys - prepared.files.keys)
        } catch (e: Exception) {
            runCatching { cancel(peer, prepared.sessionId) }
            throw e
        }
    }

    fun cancel(peer: LanToolPeer, sessionId: String) {
        execute(Request.Builder().url(peer.url("${LocalSendServer.API}/cancel").newBuilder()
            .addQueryParameter("sessionId", sessionId).build()).post(byteArrayOf().toRequestBody()).build())
    }

    private fun execute(request: Request): ByteArray {
        if (closed) throw IOException("File drop stopped")
        val call = http.newCall(request)
        calls.add(call)
        try {
            if (closed) { call.cancel(); throw IOException("File drop stopped") }
            return call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("LAN peer returned HTTP ${response.code}")
                val body = response.body ?: return@use byteArrayOf()
                if (body.contentLength() > 64 * 1024) throw IOException("Oversized LAN response")
                val source = body.source()
                if (source.request(64 * 1024L + 1)) throw IOException("Oversized LAN response")
                source.readByteArray()
            }
        } finally { calls.remove(call) }
    }

    override fun close() {
        closed = true
        calls.forEach { it.cancel() }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()
    }
}

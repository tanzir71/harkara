package io.github.tanzir71.harkara.tools.lan

import io.github.tanzir71.harkara.core.tools.HeldPttStream
import io.github.tanzir71.harkara.core.tools.PttChunk
import io.github.tanzir71.harkara.core.tools.PttFormat
import io.github.tanzir71.harkara.core.tools.PttReceiver
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

interface PttMicrophone : AutoCloseable {
    fun start(): Boolean
    /** At most buffer.size bytes of little-endian PCM16. close() must unblock a pending read. */
    fun read(buffer: ByteArray): Int
}

interface PttChunkTransport : AutoCloseable {
    /** Synchronous, bounded IO provides backpressure; implementations must not queue audio. */
    fun send(chunk: PttChunk)
}

/** One transport per hold. close is permanent and cancels a send racing with release. */
class HttpPttTransport(
    private val peer: LanToolPeer,
    private val token: String,
    client: OkHttpClient = OkHttpClient(),
) : PttChunkTransport {
    private val http = client.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .callTimeout(2, TimeUnit.SECONDS).connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.SECONDS).build()
    private val lock = Any()
    private var closed = false
    private var current: Call? = null

    override fun send(chunk: PttChunk) {
        require(chunk.pcm.size in 2..PttFormat.CHUNK_BYTES && chunk.pcm.size % 2 == 0)
        require(chunk.sequence in 0 until PttFormat.MAX_CHUNKS && chunk.streamId.length in 1..64)
        val url = peer.url(PttReceiver.PATH).newBuilder().addQueryParameter("token", token)
            .addQueryParameter("streamId", chunk.streamId).addQueryParameter("sequence", chunk.sequence.toString()).build()
        val call = synchronized(lock) {
            if (closed) throw IOException("PTT released")
            check(current == null) { "Concurrent PTT send" }
            http.newCall(Request.Builder().url(url).post(chunk.pcm.toRequestBody(PCM)).build()).also { current = it }
        }
        try {
            call.execute().use { if (!it.isSuccessful) throw IOException("PTT rejected: HTTP ${it.code}") }
        } finally { synchronized(lock) { current = null } }
    }

    override fun close() = synchronized(lock) {
        closed = true
        current?.cancel()
        Unit
    }

    private companion object { val PCM = "application/x-harkara-pcm16le;rate=16000;channels=1".toMediaType() }
}

/**
 * UI lifecycle gate. enable() is inert; only press() creates capture and starts a worker. Wire release()
 * to up/cancel/focus loss and close() to screen disposal, app backgrounding and module-off. No service,
 * executor, pending audio queue, or microphone while off. Each hold ends after <= 75 x 200 ms chunks.
 */
class LanPushToTalk(
    private val microphone: () -> PttMicrophone,
    private val transport: () -> PttChunkTransport,
    private val onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var enabled = false
    private var worker: Thread? = null
    private var capture: PttMicrophone? = null
    private var sending: PttChunkTransport? = null
    private var held: HeldPttStream? = null
    private var watchdog: Timer? = null
    val isHeld: Boolean get() = synchronized(lock) { held?.isHeld == true }

    fun enable() = synchronized(lock) { enabled = true }

    fun press(): Boolean = synchronized(lock) {
        if (!enabled || worker != null) return false
        val gate = HeldPttStream()
        gate.press()
        held = gate
        watchdog = Timer("harkara-ptt-deadline", true).also { timer ->
            timer.schedule(object : TimerTask() {
                override fun run() = synchronized(lock) { if (held === gate) release() }
            }, PttFormat.MAX_HOLD_MILLIS)
        }
        val thread = Thread({ runHold(gate) }, "harkara-ptt-held").apply { isDaemon = true }
        worker = thread
        thread.start()
        true
    }

    fun release() = synchronized(lock) {
        held?.release()
        held = null
        watchdog?.cancel()
        watchdog = null
        runCatching { sending?.close() }
        runCatching { capture?.close() }
        Unit
    }

    override fun close() = synchronized(lock) {
        enabled = false
        release()
    }

    private fun runHold(gate: HeldPttStream) {
        var mic: PttMicrophone? = null
        var wire: PttChunkTransport? = null
        try {
            synchronized(lock) {
                if (held !== gate || !enabled) return
                mic = microphone().also { capture = it }
                wire = transport().also { sending = it }
                if (!mic!!.start()) throw IOException("Microphone unavailable or permission denied")
            }
            val buffer = ByteArray(PttFormat.CHUNK_BYTES)
            while (gate.isHeld) {
                val count = mic!!.read(buffer)
                if (count <= 0) break
                require(count <= buffer.size && count % 2 == 0)
                val chunk = gate.next(buffer.copyOf(count)) ?: break
                wire!!.send(chunk)
            }
        } catch (e: Exception) {
            if (gate.isHeld) runCatching { onError(e) }
        } finally {
            synchronized(lock) {
                gate.release()
                watchdog?.cancel()
                watchdog = null
                runCatching { mic?.close() }
                runCatching { wire?.close() }
                capture = null
                sending = null
                if (held === gate) held = null
                worker = null
            }
        }
    }
}

package io.github.tanzir71.harkara.core.tools

import java.util.UUID

/** Fixed wire format: little-endian signed PCM16, 16 kHz mono; no codec/header supplied by the peer. */
data class PttChunk(val streamId: String, val sequence: Int, val pcm: ByteArray)

object PttFormat {
    const val SAMPLE_RATE = 16_000
    const val CHUNK_BYTES = 6_400 // 200 ms, PCM16 mono
    const val MAX_CHUNKS = 75 // 15 seconds per hold
    const val MAX_HOLD_MILLIS = 15_000L
}

/** Pure held-state gate; construction and enable never start a microphone. */
class HeldPttStream(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var id: String? = null
    private var sequence = 0
    private var deadline = 0L
    val isHeld: Boolean @Synchronized get() = id != null && nowMillis() < deadline && sequence < PttFormat.MAX_CHUNKS

    @Synchronized
    fun press() {
        check(id == null) { "Release the previous hold first" }
        id = UUID.randomUUID().toString()
        sequence = 0
        deadline = nowMillis() + PttFormat.MAX_HOLD_MILLIS
    }

    @Synchronized
    fun next(pcm: ByteArray): PttChunk? {
        if (!isHeld) return null
        require(pcm.isNotEmpty() && pcm.size <= PttFormat.CHUNK_BYTES && pcm.size % 2 == 0)
        return PttChunk(id!!, sequence++, pcm.copyOf())
    }

    @Synchronized
    fun release() { id = null }
}

/**
 * Explicit listening consent for one paired IP and a random bearer secret. Tokens must be exchanged
 * in person/QR on a trusted LAN; HTTP does not hide them from that LAN. No accumulated audio queue.
 * Callback is synchronous (apply bounded playback/backpressure), never invoked while disabled.
 */
class PttReceiver(
    private val play: (PttChunk) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var peer: String? = null
    private var secret: String? = null
    private var listenUntil = 0L
    private var stream: String? = null
    private var next = 0
    private var streamUntil = 0L
    private val seen = linkedSetOf<String>()

    /** Returns a fresh secret, valid for at most 15 min, only after the user explicitly starts listening. */
    @Synchronized
    fun enable(remoteAddress: String, durationMillis: Long = 15 * 60_000): String {
        require(remoteAddress.isNotBlank() && durationMillis in 1..15 * 60_000)
        disable()
        peer = remoteAddress
        secret = UUID.randomUUID().toString()
        listenUntil = nowMillis() + durationMillis
        return secret!!
    }

    @Synchronized
    fun disable() {
        peer = null
        secret = null
        stream = null
        seen.clear()
    }

    @Synchronized
    fun receive(remoteAddress: String, token: String, chunk: PttChunk): Boolean {
        val now = nowMillis()
        if (peer != remoteAddress || secret == null || secret != token || now >= listenUntil) return false
        if (!chunk.streamId.matches(Regex("[A-Za-z0-9_-]{1,64}")) || chunk.sequence !in 0 until PttFormat.MAX_CHUNKS ||
            chunk.pcm.isEmpty() || chunk.pcm.size > PttFormat.CHUNK_BYTES || chunk.pcm.size % 2 != 0) return false
        if (chunk.streamId != stream) {
            if (chunk.sequence != 0 || chunk.streamId in seen) return false
            // Bounded replay memory: re-pair instead of evicting IDs and accepting old audio again.
            if (seen.size >= 64) return false
            seen.add(chunk.streamId)
            stream = chunk.streamId
            next = 0
            streamUntil = now + PttFormat.MAX_HOLD_MILLIS
        }
        if (chunk.sequence != next || now >= streamUntil) return false
        next++
        play(chunk.copy(pcm = chunk.pcm.copyOf()))
        return true
    }

    internal fun handle(request: LanRequest): LanResponse {
        val chunk = PttChunk(
            request.query["streamId"] ?: throw LanHttpError(400),
            request.query["sequence"]?.toIntOrNull() ?: throw LanHttpError(400),
            request.body(PttFormat.CHUNK_BYTES),
        )
        return LanResponse(if (receive(request.remoteAddress, request.query["token"] ?: "", chunk)) 200 else 403)
    }

    companion object { const val PATH = "/api/harkara/ptt/v1/chunk" }
}

package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.wire.FrameType
import io.github.tanzir71.harkara.core.wire.MeshFrame
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant

/**
 * Splits a frame that does not fit a link's MTU into [FrameType.FRAGMENT] frames. Fragments are link-local
 * (ttl 1): each hop reassembles, checks the event, then fragments again for its own links.
 *
 * Fragment payload: 8-byte set id (SHA-256 prefix of the whole frame) | u16 index | u16 count |
 * u32 total length | chunk.
 */
object Fragmenter {
    const val FRAGMENT_HEADER_BYTES: Int = 16

    /** Frame header (5) + payload length varint (up to 3) + fragment header. */
    private const val OVERHEAD = 5 + 3 + FRAGMENT_HEADER_BYTES

    fun split(frame: MeshFrame, mtu: Int): List<MeshFrame> {
        val whole = frame.encode()
        if (whole.size <= mtu) return listOf(frame)
        val chunk = mtu - OVERHEAD
        require(chunk > 0) { "MTU $mtu is too small for fragments" }
        val count = (whole.size + chunk - 1) / chunk
        require(count <= 0xFFFF) { "too many fragments" }
        val setId = Hashes.sha256(whole).copyOf(8)
        return (0 until count).map { index ->
            val start = index * chunk
            val end = minOf(whole.size, start + chunk)
            val payload = ByteBuffer.allocate(FRAGMENT_HEADER_BYTES + (end - start))
                .put(setId)
                .putShort(index.toShort())
                .putShort(count.toShort())
                .putInt(whole.size)
                .put(whole, start, end - start)
                .array()
            MeshFrame(FrameType.FRAGMENT, ttl = 1, hops = 0, flags = 0, payload = payload)
        }
    }
}

/**
 * Rebuilds frames from fragments. Incomplete sets are dropped after [timeout] (60 s by default), and at
 * most [maxPending] sets of at most [maxFrameBytes] each are held, so a flood of fragments cannot fill memory.
 */
class FragmentReassembler(
    private val timeout: Duration = Duration.ofSeconds(60),
    private val maxPending: Int = 64,
    private val maxFrameBytes: Int = 70 * 1024,
) {
    private val pending = LinkedHashMap<String, Pending>()

    /** Number of incomplete sets held right now. */
    val pendingSets: Int
        get() = pending.size

    /** Accepts one fragment; returns the whole frame when the last piece arrives, otherwise null. */
    fun accept(fragment: MeshFrame, now: Instant): MeshFrame? {
        require(fragment.type == FrameType.FRAGMENT) { "expected a FRAGMENT frame" }
        expire(now)
        val payload = fragment.payload
        if (payload.size < Fragmenter.FRAGMENT_HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload)
        val setIdBytes = ByteArray(8).also(buffer::get)
        val index = buffer.short.toInt() and 0xFFFF
        val count = buffer.short.toInt() and 0xFFFF
        val total = buffer.int
        if (count == 0 || index >= count || total <= 0 || total > maxFrameBytes) return null
        val chunk = ByteArray(buffer.remaining()).also(buffer::get)
        val setId = Hex.encode(setIdBytes)
        val state = pending[setId] ?: Pending(now, count, total).also {
            if (pending.size >= maxPending) pending.remove(pending.keys.first())
            pending[setId] = it
        }
        if (state.count != count || state.total != total) return null
        state.chunks[index] = chunk
        if (state.chunks.size < count) return null
        pending.remove(setId)
        val whole = ByteArray(total)
        var offset = 0
        for (i in 0 until count) {
            val piece = state.chunks.getValue(i)
            if (offset + piece.size > total) return null
            piece.copyInto(whole, offset)
            offset += piece.size
        }
        if (offset != total || !Hashes.sha256(whole).copyOf(8).contentEquals(setIdBytes)) return null
        return runCatching { MeshFrame.decode(whole) }.getOrNull()
    }

    private fun expire(now: Instant) {
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            if (Duration.between(iterator.next().startedAt, now) > timeout) iterator.remove()
        }
    }

    private class Pending(val startedAt: Instant, val count: Int, val total: Int) {
        val chunks = HashMap<Int, ByteArray>()
    }
}

package io.github.tanzir71.harkara.core

import java.time.Duration
import java.time.Instant
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.min

/** Splits oversized mesh packets into MTU-bounded fragment packets. */
object Fragmenter {
    /** Splits a packet into fragment packets whose encoded size is at most [mtu]. */
    fun fragment(packet: MeshPacket, mtu: Int): List<MeshPacket> {
        if (PacketCodec.encode(packet).size <= mtu) return listOf(packet)
        val signature = packet.signature ?: ByteArray(0)
        require(signature.size <= UByte.MAX_VALUE.toInt()) { "signature too large" }
        val metadataSize = FRAGMENT_METADATA_SIZE + signature.size
        val maxChunk = mtu - PacketCodec.HEADER_SIZE - metadataSize
        require(maxChunk > 0) { "MTU $mtu too small for fragment metadata" }
        val count = ceil(packet.payload.size.toDouble() / maxChunk.toDouble()).toInt()
        require(count <= UShort.MAX_VALUE.toInt()) { "too many fragments" }
        return (0 until count).map { index ->
            val start = index * maxChunk
            val end = min(packet.payload.size, start + maxChunk)
            val chunk = packet.payload.copyOfRange(start, end)
            val payload = ByteBuffer.allocate(metadataSize + chunk.size).apply {
                put(packet.type.id.toByte())
                put(packet.flags.toByte())
                putLong(packet.msgId)
                putLong(packet.timestamp.toEpochMilli())
                putInt(packet.payload.size)
                putShort(index.toShort())
                putShort(count.toShort())
                put(signature.size.toByte())
                put(signature)
                put(chunk)
            }.array()
            MeshPacket(
                version = packet.version,
                type = PacketType.FRAGMENT,
                ttl = packet.ttl,
                flags = PacketFlags.NONE,
                msgId = packet.msgId xor ((index.toLong() + 1) shl 48),
                senderId = packet.senderId,
                timestamp = packet.timestamp,
                payload = payload,
            )
        }
    }
}

/** Reassembles fragment packets into their original mesh packet. */
class FragmentReassembler(private val timeout: Duration) {
    private val pending = mutableMapOf<FragmentKey, PendingFragments>()

    /** Accepts one fragment and returns the full packet once all fragments arrive. */
    fun accept(fragment: MeshPacket, now: Instant): MeshPacket? {
        require(fragment.type == PacketType.FRAGMENT) { "expected FRAGMENT packet" }
        val decoded = FragmentPayload.decode(fragment.payload)
        val key = FragmentKey(fragment.senderId, decoded.originalMsgId)
        val existing = pending[key]
        val state = if (existing == null || Duration.between(existing.startedAt, now) > timeout) {
            PendingFragments(
                startedAt = now,
                version = fragment.version,
                ttl = fragment.ttl,
                senderId = fragment.senderId,
                originalType = decoded.originalType,
                originalFlags = decoded.originalFlags,
                originalMsgId = decoded.originalMsgId,
                originalTimestamp = decoded.originalTimestamp,
                originalPayloadLength = decoded.originalPayloadLength,
                count = decoded.count,
                signature = decoded.signature,
            ).also { pending[key] = it }
        } else {
            existing
        }
        state.chunks[decoded.index] = decoded.chunk
        if (state.chunks.size != state.count) return null
        val payload = ByteArray(state.originalPayloadLength)
        var offset = 0
        (0 until state.count).forEach { index ->
            val chunk = state.chunks[index] ?: error("missing fragment $index")
            chunk.copyInto(payload, offset)
            offset += chunk.size
        }
        require(offset == payload.size) { "reassembled payload length mismatch" }
        pending.remove(key)
        return MeshPacket(
            version = state.version,
            type = state.originalType,
            ttl = state.ttl,
            flags = state.originalFlags,
            msgId = state.originalMsgId,
            senderId = state.senderId,
            timestamp = state.originalTimestamp,
            payload = payload,
            signature = state.signature,
        )
    }

    private data class FragmentKey(val senderId: NearId, val msgId: Long)

    private data class PendingFragments(
        val startedAt: Instant,
        val version: Int,
        val ttl: Int,
        val senderId: NearId,
        val originalType: PacketType,
        val originalFlags: Int,
        val originalMsgId: Long,
        val originalTimestamp: Instant,
        val originalPayloadLength: Int,
        val count: Int,
        val signature: ByteArray?,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private data class FragmentPayload(
        val originalType: PacketType,
        val originalFlags: Int,
        val originalMsgId: Long,
        val originalTimestamp: Instant,
        val originalPayloadLength: Int,
        val index: Int,
        val count: Int,
        val signature: ByteArray?,
        val chunk: ByteArray,
    ) {
        companion object {
            fun decode(bytes: ByteArray): FragmentPayload {
                require(bytes.size >= FRAGMENT_METADATA_SIZE) { "fragment payload too short" }
                val buffer = ByteBuffer.wrap(bytes)
                val originalType = PacketType.fromId(buffer.get().toInt() and 0xff)
                val originalFlags = buffer.get().toInt() and 0xff
                val originalMsgId = buffer.long
                val originalTimestamp = Instant.ofEpochMilli(buffer.long)
                val originalPayloadLength = buffer.int
                val index = buffer.short.toInt() and 0xffff
                val count = buffer.short.toInt() and 0xffff
                val signatureLength = buffer.get().toInt() and 0xff
                require(bytes.size >= FRAGMENT_METADATA_SIZE + signatureLength) { "fragment signature truncated" }
                val signature = if (signatureLength == 0) null else ByteArray(signatureLength).also(buffer::get)
                val chunk = ByteArray(buffer.remaining()).also(buffer::get)
                return FragmentPayload(
                    originalType = originalType,
                    originalFlags = originalFlags,
                    originalMsgId = originalMsgId,
                    originalTimestamp = originalTimestamp,
                    originalPayloadLength = originalPayloadLength,
                    index = index,
                    count = count,
                    signature = signature,
                    chunk = chunk,
                )
            }
        }
    }
}

private const val FRAGMENT_METADATA_SIZE = 27

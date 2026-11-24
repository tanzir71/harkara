package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import java.io.ByteArrayOutputStream

/** What a mesh frame carries. */
enum class FrameType(val id: Int) {
    /** One binary event ([BinaryEventCodec]). */
    EVENT(1),

    /** A piece of a larger frame, for links with a small MTU. */
    FRAGMENT(2),

    /** Event ids a peer holds, offered on connect (partition heal). */
    DIGEST(3),

    /** Event ids a peer wants from a digest. */
    PULL(4),

    /** The sender's daily-rotating 16-byte presence id. */
    PRESENCE(5),
    ;

    companion object {
        fun fromId(id: Int): FrameType = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown frame type $id")
    }
}

/**
 * Bluetooth mesh frame. The hop count lives here, outside the signed event, so relaying never touches the
 * signature:
 *
 * ```
 * u8      version (1)
 * u8      type    (FrameType)
 * u8      ttl     (most hops this frame may travel; set by the sender)
 * u8      hops    (hops travelled so far)
 * u8      flags   (reserved, 0)
 * varint  payload length + payload
 * ```
 */
class MeshFrame(
    val type: FrameType,
    val ttl: Int,
    val hops: Int,
    val flags: Int,
    payload: ByteArray,
) {
    private val bytes = payload.copyOf()

    init {
        require(ttl in 0..255 && hops in 0..255 && flags in 0..255) { "header fields are single bytes" }
    }

    val payload: ByteArray
        get() = bytes.copyOf()

    val payloadSize: Int
        get() = bytes.size

    /** True when a receiver may pass this frame on (one more hop stays within [ttl]). */
    val canRelay: Boolean
        get() = hops + 1 < ttl

    /** The copy a relay sends on: one more hop, same payload. */
    fun forRelay(): MeshFrame = MeshFrame(type, ttl, hops + 1, flags, bytes)

    /** The event inside an [FrameType.EVENT] frame. */
    fun event(): Event {
        require(type == FrameType.EVENT) { "not an event frame" }
        return BinaryEventCodec.decode(bytes)
    }

    /** The ids inside a [FrameType.DIGEST] or [FrameType.PULL] frame. */
    fun ids(): List<String> {
        require(type == FrameType.DIGEST || type == FrameType.PULL) { "not an id-list frame" }
        val r = Reader(bytes)
        val count = r.varint()
        require(count in 0..(bytes.size / 32).toLong()) { "bad id count" }
        val ids = List(count.toInt()) { Hex.encode(r.bytes(32)) }
        require(r.done()) { "trailing bytes in id list" }
        return ids
    }

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream(8 + bytes.size)
        out.write(VERSION)
        out.write(type.id)
        out.write(ttl)
        out.write(hops)
        out.write(flags)
        Varint.write(out, bytes.size.toLong())
        out.write(bytes)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is MeshFrame && other.type == type && other.ttl == ttl &&
        other.hops == hops && other.flags == flags && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = ((type.hashCode() * 31 + ttl) * 31 + hops) * 31 + bytes.contentHashCode()

    override fun toString(): String = "MeshFrame($type, ttl=$ttl, hops=$hops, ${bytes.size} B)"

    companion object {
        const val VERSION: Int = 1

        /** Default reach: up to 7 hops. */
        const val DEFAULT_TTL: Int = 7

        fun event(event: Event, ttl: Int = DEFAULT_TTL): MeshFrame = MeshFrame(FrameType.EVENT, ttl, 0, 0, BinaryEventCodec.encode(event))

        /** Digest and pull frames go one hop only. */
        fun digest(ids: Collection<String>): MeshFrame = MeshFrame(FrameType.DIGEST, 1, 0, 0, encodeIds(ids))

        fun pull(ids: Collection<String>): MeshFrame = MeshFrame(FrameType.PULL, 1, 0, 0, encodeIds(ids))

        fun presence(presenceId: ByteArray): MeshFrame {
            require(presenceId.size == 16) { "presence ids are 16 bytes" }
            return MeshFrame(FrameType.PRESENCE, 1, 0, 0, presenceId)
        }

        fun decode(bytes: ByteArray): MeshFrame {
            val r = Reader(bytes)
            val version = r.u8()
            require(version == VERSION) { "unsupported frame version $version" }
            val type = FrameType.fromId(r.u8())
            val ttl = r.u8()
            val hops = r.u8()
            val flags = r.u8()
            val payload = r.lengthPrefixed()
            require(r.done()) { "trailing bytes after frame" }
            return MeshFrame(type, ttl, hops, flags, payload)
        }

        private fun encodeIds(ids: Collection<String>): ByteArray {
            val out = ByteArrayOutputStream(4 + ids.size * 32)
            Varint.write(out, ids.size.toLong())
            ids.forEach { id ->
                require(Hex.isLowerHex(id, 32)) { "event ids are 32 bytes of hex" }
                out.write(Hex.decode(id))
            }
            return out.toByteArray()
        }
    }
}

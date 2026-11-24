package io.github.tanzir71.harkara.core

import java.time.Instant
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Stable 16-byte public identity used on the mesh. */
data class NearId(val bytes: ByteArray) {
    init {
        require(bytes.size == SIZE) { "NearId must be 16 bytes" }
    }

    override fun equals(other: Any?): Boolean = other is NearId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Encodes this NearID as unpadded RFC 4648 base32. */
    fun toBase32(): String {
        var buffer = 0
        var bitsLeft = 0
        val output = StringBuilder()
        bytes.forEach { raw ->
            buffer = (buffer shl 8) or (raw.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 31
                output.append(BASE32_ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            output.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 31])
        }
        return output.toString()
    }

    companion object {
        /** Number of bytes in a NearID. */
        const val SIZE: Int = 16

        /** Derives a NearID from an encoded Ed25519 public key. */
        fun fromPublicKey(ed25519PublicEncoded: ByteArray): NearId {
            val digest = MessageDigest.getInstance("SHA-256").digest(ed25519PublicEncoded)
            return NearId(digest.copyOf(SIZE))
        }

        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}

/** Mesh packet type identifiers. */
enum class PacketType(val id: Int) {
    ANNOUNCE(1),
    CHAT_BROADCAST(2),
    DM(3),
    POST(4),
    POST_REQUEST(5),
    SOS(6),
    ACK(7),
    FRAGMENT(8),
    SYNC_DIGEST(9),
    SYNC_PULL(10);

    companion object {
        /** Resolves a binary packet type id. */
        fun fromId(id: Int): PacketType = entries.firstOrNull { it.id == id }
            ?: error("Unknown packet type: $id")
    }
}

/** Bit flags encoded in the packet header. */
object PacketFlags {
    /** No packet flags set. */
    const val NONE: Int = 0

    /** Packet carries a 64-byte Ed25519 signature after the payload. */
    const val SIGNED: Int = 1
}

/** Wire packet exchanged by mesh and sync transports. */
data class MeshPacket(
    val version: Int,
    val type: PacketType,
    val ttl: Int,
    val flags: Int,
    val msgId: Long,
    val senderId: NearId,
    val timestamp: Instant,
    val payload: ByteArray,
    val signature: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is MeshPacket &&
        version == other.version &&
        type == other.type &&
        ttl == other.ttl &&
        flags == other.flags &&
        msgId == other.msgId &&
        senderId == other.senderId &&
        timestamp.equals(other.timestamp) &&
        payload.contentEquals(other.payload) &&
        ((signature == null && other.signature == null) || (signature != null && other.signature != null && signature.contentEquals(other.signature)))

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + type.hashCode()
        result = 31 * result + ttl
        result = 31 * result + flags
        result = 31 * result + msgId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }

    /** Returns a copy ready for one relay hop. */
    fun forRelay(): MeshPacket = copy(ttl = ttl - 1)

    companion object {
        /** Creates a default local chat packet. */
        fun chat(msgId: Long, senderId: NearId, payload: ByteArray, now: Instant): MeshPacket =
            MeshPacket(
                version = 1,
                type = PacketType.CHAT_BROADCAST,
                ttl = 7,
                flags = PacketFlags.NONE,
                msgId = msgId,
                senderId = senderId,
                timestamp = now,
                payload = payload,
            )
    }
}

/** Encodes and decodes Harkara binary mesh packets. */
object PacketCodec {
    /** Fixed header byte count before payload bytes. */
    const val HEADER_SIZE: Int = 38

    /** Encodes a packet into its binary wire form. */
    fun encode(packet: MeshPacket): ByteArray {
        require(packet.version in 0..255) { "version must fit in one byte" }
        require(packet.ttl in 0..255) { "ttl must fit in one byte" }
        require(packet.flags in 0..255) { "flags must fit in one byte" }
        require(packet.payload.size <= UShort.MAX_VALUE.toInt()) { "payload too large" }
        val signature = packet.signature
        val signed = packet.flags and PacketFlags.SIGNED != 0
        require(!signed || signature?.size == SIGNATURE_SIZE) { "signed packets require a 64-byte signature" }
        val size = HEADER_SIZE + packet.payload.size + if (signed) SIGNATURE_SIZE else 0
        return ByteBuffer.allocate(size).apply {
            put(packet.version.toByte())
            put(packet.type.id.toByte())
            put(packet.ttl.toByte())
            put(packet.flags.toByte())
            putLong(packet.msgId)
            put(packet.senderId.bytes)
            putLong(packet.timestamp.toEpochMilli())
            putShort(packet.payload.size.toShort())
            put(packet.payload)
            if (signed) {
                put(signature)
            }
        }.array()
    }

    /** Decodes a packet from its binary wire form. */
    fun decode(bytes: ByteArray): MeshPacket {
        require(bytes.size >= HEADER_SIZE) { "packet shorter than header" }
        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.get().toInt() and 0xff
        val type = PacketType.fromId(buffer.get().toInt() and 0xff)
        val ttl = buffer.get().toInt() and 0xff
        val flags = buffer.get().toInt() and 0xff
        val msgId = buffer.long
        val senderBytes = ByteArray(NearId.SIZE).also(buffer::get)
        val timestamp = Instant.ofEpochMilli(buffer.long)
        val payloadLength = buffer.short.toInt() and 0xffff
        val signed = flags and PacketFlags.SIGNED != 0
        val expected = HEADER_SIZE + payloadLength + if (signed) SIGNATURE_SIZE else 0
        require(bytes.size == expected) { "packet length mismatch: expected $expected, got ${bytes.size}" }
        val payload = ByteArray(payloadLength).also(buffer::get)
        val signature = if (signed) ByteArray(SIGNATURE_SIZE).also(buffer::get) else null
        return MeshPacket(
            version = version,
            type = type,
            ttl = ttl,
            flags = flags,
            msgId = msgId,
            senderId = NearId(senderBytes),
            timestamp = timestamp,
            payload = payload,
            signature = signature,
        )
    }

    /** Returns canonical packet bytes used for Ed25519 signatures. */
    fun signingBytes(packet: MeshPacket): ByteArray =
        encode(packet.copy(flags = packet.flags and PacketFlags.SIGNED.inv(), signature = null))

    private const val SIGNATURE_SIZE = 64
}

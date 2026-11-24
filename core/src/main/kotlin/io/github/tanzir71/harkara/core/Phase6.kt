package io.github.tanzir71.harkara.core

import java.security.MessageDigest
import java.util.Base64

/** Mesh-sized media payload and preview metadata. */
data class MeshMedia(
    /** Encoded image bytes. */
    val bytes: ByteArray,
    /** MIME type for [bytes]. */
    val mimeType: String,
    /** Tiny placeholder string shown until full media arrives. */
    val blurHash: String,
)

/** Media preparation contract for mesh transport. */
object MediaCompressor {
    /** Compresses image data for mesh transport with a 48 KB default bound. */
    fun compressForMesh(source: ByteArray, maxBytes: Int = 48 * 1024): MeshMedia {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val bytes = if (source.size <= maxBytes) source.copyOf() else source.copyOf(maxBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(source)
        val blurHash = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(8))
        return MeshMedia(bytes = bytes, mimeType = "image/webp", blurHash = blurHash)
    }
}

/** Supported reaction kinds. */
enum class ReactionKind {
    LIKE,
    REPLY,
}

/** Signed reaction event after packet verification. */
data class ReactionEvent(
    /** Referenced post id. */
    val postId: String,
    /** Reaction author. */
    val author: NearId,
    /** Reaction kind. */
    val kind: ReactionKind,
    /** Monotonic per-author count. */
    val count: Int,
)

/** G-Counter-style reaction merge state. */
class ReactionCounter {
    private data class Key(val postId: String, val author: NearId, val kind: ReactionKind)

    private val counts = mutableMapOf<Key, Int>()

    /** Merges one event by taking the max per `(postId, author, kind)`. */
    fun merge(event: ReactionEvent) {
        require(event.count >= 0) { "reaction count cannot be negative" }
        val key = Key(event.postId, event.author, event.kind)
        counts[key] = maxOf(counts[key] ?: 0, event.count)
    }

    /** Returns the merged total for a post/kind. */
    fun total(postId: String, kind: ReactionKind): Int =
        counts.filterKeys { it.postId == postId && it.kind == kind }.values.sum()
}

/** Delivery state for DMs and broadcasts that request ACKs. */
enum class DeliveryState {
    PENDING,
    MESH_DELIVERED,
    ACKED,
}

/** Monotonic delivery-state machine. */
class AckStateMachine {
    private val states = mutableMapOf<Long, DeliveryState>()

    /** Ensures a message is tracked as pending unless already advanced. */
    fun markPending(msgId: Long) {
        states.putIfAbsent(msgId, DeliveryState.PENDING)
    }

    /** Marks a message delivered through mesh. */
    fun markMeshDelivered(msgId: Long) {
        advance(msgId, DeliveryState.MESH_DELIVERED)
    }

    /** Marks a message acknowledged by the recipient. */
    fun markAcked(msgId: Long) {
        advance(msgId, DeliveryState.ACKED)
    }

    /** Returns current state, defaulting to pending. */
    fun stateOf(msgId: Long): DeliveryState = states[msgId] ?: DeliveryState.PENDING

    private fun advance(msgId: Long, target: DeliveryState) {
        val current = stateOf(msgId)
        if (target.ordinal > current.ordinal) {
            states[msgId] = target
        } else {
            states.putIfAbsent(msgId, current)
        }
    }
}

/** Channel-scoped public chat payload. */
data class ChannelMessage(
    /** Hashtag channel name. */
    val channel: String,
    /** Message body. */
    val body: String,
) {
    /** Encodes the payload for CHAT_BROADCAST packets. */
    fun toPayload(): ByteArray {
        require(channel.startsWith("#")) { "channel must start with #" }
        require('\n' !in channel) { "channel cannot contain newline" }
        return "$channel\n$body".encodeToByteArray()
    }

    companion object {
        /** Decodes a channel payload. */
        fun fromPayload(payload: ByteArray): ChannelMessage {
            val text = payload.decodeToString()
            val separator = text.indexOf('\n')
            require(separator > 0) { "missing channel separator" }
            return ChannelMessage(
                channel = text.substring(0, separator),
                body = text.substring(separator + 1),
            )
        }
    }
}

/** Predicate for channel chat packets. */
class ChannelFilter(private val channel: String) {
    /** Returns true when [packet] is a chat broadcast for this channel. */
    fun matches(packet: MeshPacket): Boolean =
        packet.type == PacketType.CHAT_BROADCAST &&
            runCatching { ChannelMessage.fromPayload(packet.payload).channel == channel }.getOrDefault(false)
}

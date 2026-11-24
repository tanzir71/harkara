package io.github.tanzir71.harkara.core

import java.time.Duration
import java.time.Instant

/** Expiring LRU cache for mesh message ids. */
class LruDedupeCache(private val capacity: Int, private val expiry: Duration) {
    private val seen = LinkedHashMap<Long, Instant>(capacity, 0.75f, true)

    /** Marks a message id seen, returning true when it was not already live. */
    fun markIfNew(msgId: Long, now: Instant): Boolean {
        purgeExpired(now)
        val previous = seen[msgId]
        if (previous != null && Duration.between(previous, now) < expiry) {
            return false
        }
        seen[msgId] = now
        while (seen.size > capacity) {
            val eldest = seen.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        return true
    }

    private fun purgeExpired(now: Instant) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (Duration.between(entry.value, now) >= expiry) {
                iterator.remove()
            }
        }
    }
}

/** Per-peer token bucket used by congestion control. */
class TokenBucket(private val ratePerSecond: Double, private val capacity: Int) {
    private var tokens = capacity.toDouble()
    private var lastRefill: Instant? = null

    /** Attempts to consume one token at the provided time. */
    fun tryConsume(now: Instant): Boolean {
        refill(now)
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    private fun refill(now: Instant) {
        val previous = lastRefill
        lastRefill = now
        if (previous == null) return
        val elapsed = Duration.between(previous, now).toNanos().coerceAtLeast(0).toDouble() / 1_000_000_000.0
        tokens = minOf(capacity.toDouble(), tokens + elapsed * ratePerSecond)
    }
}

/** Bounded queue that drops lowest-priority mesh packets first. */
class OutboundRelayQueue(private val capacity: Int) {
    private val packets = mutableListOf<MeshPacket>()

    /** Offers a packet, evicting lower priority work when needed. */
    fun offer(packet: MeshPacket): Boolean {
        if (packets.size < capacity) {
            packets += packet
            return true
        }
        val lowest = packets.withIndex().minByOrNull { priorityOf(it.value.type) } ?: return false
        if (priorityOf(packet.type) <= priorityOf(lowest.value.type)) {
            return false
        }
        packets.removeAt(lowest.index)
        packets += packet
        return true
    }

    /** Drains packets highest priority first. */
    fun drain(): List<MeshPacket> {
        val drained = packets.sortedByDescending { priorityOf(it.type) }
        packets.clear()
        return drained
    }

    private fun priorityOf(type: PacketType): Int = when (type) {
        PacketType.SOS -> 5
        PacketType.DM, PacketType.ACK -> 4
        PacketType.CHAT_BROADCAST, PacketType.ANNOUNCE -> 3
        PacketType.POST, PacketType.POST_REQUEST, PacketType.FRAGMENT -> 2
        PacketType.SYNC_DIGEST, PacketType.SYNC_PULL -> 1
    }
}

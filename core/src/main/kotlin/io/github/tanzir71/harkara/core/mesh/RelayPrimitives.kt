package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.wire.FrameType
import io.github.tanzir71.harkara.core.wire.MeshFrame
import java.time.Duration
import java.time.Instant

/** Expiring LRU set of event ids already handled. */
class DedupeCache(private val capacity: Int = 10_000, private val expiry: Duration = Duration.ofMinutes(30)) {
    private val seen = LinkedHashMap<String, Instant>(capacity, 0.75f, true)

    /** True when [id] was marked within the expiry window. */
    fun contains(id: String, now: Instant): Boolean {
        val at = seen[id] ?: return false
        return Duration.between(at, now) < expiry
    }

    /** Marks [id] seen, returning true when it was not already live. */
    fun markIfNew(id: String, now: Instant): Boolean {
        purgeExpired(now)
        if (contains(id, now)) return false
        seen[id] = now
        while (seen.size > capacity) {
            val eldest = seen.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return true
    }

    val size: Int
        get() = seen.size

    private fun purgeExpired(now: Instant) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (Duration.between(iterator.next().value, now) >= expiry) iterator.remove()
        }
    }
}

/** Per-peer token bucket for link congestion control. */
class TokenBucket(private val ratePerSecond: Double, private val capacity: Int) {
    private var tokens = capacity.toDouble()
    private var lastRefill: Instant? = null

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

/** Relay priority: SOS > DMs/gift wraps > chat > posts > sync. */
object MeshPriority {
    const val SOS = 5
    const val PRIVATE = 4
    const val CHAT = 3
    const val POST = 2
    const val SYNC = 1

    fun of(event: Event): Int = ofKind(event.kind)

    fun ofKind(kind: Int): Int = when (kind) {
        Kinds.SOS, Kinds.SOS_UPDATE, Kinds.CHECK_IN -> SOS
        Kinds.GIFT_WRAP, Kinds.EPHEMERAL_GIFT_WRAP, Kinds.SEAL -> PRIVATE
        Kinds.AREA_CHAT -> CHAT
        else -> POST
    }

    /** Priority of a frame; event frames are decoded to read their kind. */
    fun of(frame: MeshFrame): Int = when (frame.type) {
        FrameType.EVENT -> runCatching { of(frame.event()) }.getOrDefault(POST)
        FrameType.PRESENCE -> CHAT
        FrameType.FRAGMENT -> POST
        FrameType.DIGEST, FrameType.PULL -> SYNC
    }
}

/** Bounded outbound queue that drops the lowest-priority frame first when full. */
class OutboundRelayQueue(private val capacity: Int) {
    private data class Entry(val frame: MeshFrame, val priority: Int, val order: Long)

    private val entries = mutableListOf<Entry>()
    private var counter = 0L

    val size: Int
        get() = entries.size

    fun offer(frame: MeshFrame, priority: Int = MeshPriority.of(frame)): Boolean {
        val entry = Entry(frame, priority, counter++)
        if (entries.size < capacity) {
            entries += entry
            return true
        }
        val lowest = entries.withIndex().minWithOrNull(compareBy<IndexedValue<Entry>> { it.value.priority }.thenByDescending { it.value.order })
            ?: return false
        if (priority <= lowest.value.priority) return false
        entries.removeAt(lowest.index)
        entries += entry
        return true
    }

    /** Highest priority first; first-in first-out within a priority. */
    fun drain(): List<MeshFrame> {
        val drained = entries.sortedWith(compareByDescending<Entry> { it.priority }.thenBy { it.order }).map { it.frame }
        entries.clear()
        return drained
    }
}

package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.SosPolicy
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.store.EventStore
import io.github.tanzir71.harkara.core.wire.FrameType
import io.github.tanzir71.harkara.core.wire.MeshFrame
import java.time.Duration
import java.time.Instant

/** Why a relay dropped an event. */
enum class DropReason { MALFORMED, DUPLICATE, INVALID, EXPIRED, FROM_THE_FUTURE, RATE_LIMITED, SOS_LIMITED }

/** What a relay decided about one incoming event frame. */
sealed interface RelayDecision {
    /** The event is new and valid: show/store it, and send [relay] on to other neighbours when not null. */
    data class Accepted(val event: Event, val hops: Int, val relay: MeshFrame?) : RelayDecision

    data class Dropped(val reason: DropReason) : RelayDecision
}

/**
 * The per-phone mesh rules, applied to every event frame before anything is stored or passed on:
 * decode → already seen? → id + BIP-340 signature + size → NIP-40 expiry → not too far in the future →
 * peer rate limits (posts) and SOS policy → store-and-forward → relay with one more hop while within TTL.
 * Only valid events are marked seen, so a forged copy cannot block the real one.
 */
class MeshRelay(
    val store: EventStore,
    private val rateLimiter: PeerRateLimiter = PeerRateLimiter(),
    private val sosPolicy: SosPolicy = SosPolicy(),
    private val dedupe: DedupeCache = DedupeCache(),
    private val maxFutureSkew: Duration = Duration.ofMinutes(15),
) {
    /** Marks a locally created event as seen and keeps it; returns the frame to broadcast. */
    fun originate(event: Event, now: Instant, ttl: Int = MeshFrame.DEFAULT_TTL): MeshFrame {
        dedupe.markIfNew(event.id, now)
        rateLimiter.tryAccept(event)
        sosPolicy.accept(event)
        store.ingest(event, now.epochSecond)
        return MeshFrame.event(event, ttl)
    }

    /** True when [id] was handled recently (used to skip pulls for events already seen). */
    fun hasSeen(id: String, now: Instant): Boolean = dedupe.contains(id, now) || id in store

    fun onFrame(frame: MeshFrame, now: Instant): RelayDecision {
        if (frame.type != FrameType.EVENT) return RelayDecision.Dropped(DropReason.MALFORMED)
        val event = runCatching { frame.event() }.getOrElse { return RelayDecision.Dropped(DropReason.MALFORMED) }
        return onEvent(event, frame, now)
    }

    fun onEvent(event: Event, frame: MeshFrame, now: Instant): RelayDecision {
        if (dedupe.contains(event.id, now)) return RelayDecision.Dropped(DropReason.DUPLICATE)
        if (!event.validate()) return RelayDecision.Dropped(DropReason.INVALID)
        dedupe.markIfNew(event.id, now)
        if (event.isExpired(now.epochSecond)) return RelayDecision.Dropped(DropReason.EXPIRED)
        if (event.createdAt > now.epochSecond + maxFutureSkew.seconds) return RelayDecision.Dropped(DropReason.FROM_THE_FUTURE)
        if (!rateLimiter.tryAccept(event)) return RelayDecision.Dropped(DropReason.RATE_LIMITED)
        if (!sosPolicy.accept(event)) return RelayDecision.Dropped(DropReason.SOS_LIMITED)
        val stored = store.ingest(event, now.epochSecond)
        val relay = if (stored.shouldRelay && frame.canRelay) frame.forRelay() else null
        return RelayDecision.Accepted(event, frame.hops + 1, relay)
    }
}

package io.github.tanzir71.harkara.places

import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.keys.KeyRing
import io.github.tanzir71.harkara.core.location.LiveDuration
import io.github.tanzir71.harkara.core.location.LivePerson
import io.github.tanzir71.harkara.core.location.LiveShareTracker
import io.github.tanzir71.harkara.core.location.LiveShares
import io.github.tanzir71.harkara.core.location.LiveVerdict
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** The share this phone is running. */
data class OutgoingShare(
    val shareId: String,
    val recipients: List<String>,
    val end: Long,
    val seq: Long,
    val lastFix: Fix?,
    val sent: Int,
)

/** Starts/stops the foreground service that keeps a user-started share alive with the screen off. */
interface LiveShareHost {
    fun started(share: OutgoingShare) {}
    fun stopped() {}
}

/**
 * Live location: our own share (session key, `seq`, end time, GPS only while it runs) and the people
 * sharing with us (replay-protected by [LiveShareTracker]). Points go straight to the relays as ephemeral
 * gift wraps; they are never queued or stored.
 */
class LiveShareRepository(
    private val outbox: Outbox,
    private val pool: RelayPool,
    private val profile: () -> KeyPair,
    private val keyRing: () -> KeyRing,
    private val location: LocationSource,
    private val scope: CoroutineScope,
    private val displayName: () -> String? = { null },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    var host: LiveShareHost = object : LiveShareHost {},
) {
    private val now get() = nowMillis() / 1000
    private val tracker = LiveShareTracker { now }
    private val mutableOutgoing = MutableStateFlow<OutgoingShare?>(null)
    val outgoing: StateFlow<OutgoingShare?> = mutableOutgoing.asStateFlow()
    private val changes = MutableStateFlow(0)
    private val lock = Mutex()
    private var job: Job? = null

    fun startIngest(inbox: GiftInbox) {
        inbox.register(setOf(Kinds.LIVE_LOCATION)) { rumor, _ -> receive(rumor) }
    }

    fun receive(rumor: Rumor): LiveVerdict {
        if (rumor.pubkey == profile().pubkeyHex) return LiveVerdict.NOT_LIVE
        val verdict = tracker.accept(rumor)
        if (verdict == LiveVerdict.ACCEPTED || verdict == LiveVerdict.STARTED || verdict == LiveVerdict.EXTENDED || verdict == LiveVerdict.STOPPED) changes.value++
        return verdict
    }

    /** People sharing with us now; re-read on every point and every 30 s so ended shares drop off. */
    fun people(): Flow<List<LivePerson>> =
        combine(changes, flow { while (true) { emit(Unit); delay(30_000) } }) { _, _ -> tracker.active() }

    /**
     * Starts sharing with [recipients] for [duration]: sends the start (stored wrap that expires at the
     * end), then one point per fix (at most every [minIntervalMillis] unless moved 25 m) until the end.
     */
    suspend fun start(recipients: List<String>, duration: LiveDuration, minIntervalMillis: Long = 20_000): OutgoingShare = lock.withLock {
        stopLocked()
        val people = recipients.distinct().filter { it != profile().pubkeyHex }
        require(people.isNotEmpty()) { "share with at least one person" }
        val shareId = UUID.randomUUID().toString()
        val session = keyRing().session(shareId)
        val end = now + duration.seconds
        LiveShares.startWraps(profile(), session, shareId, people, end, now, displayName()).forEach { outbox.enqueue(it, Priority.MESSAGE) }
        val share = OutgoingShare(shareId, people, end, 0, null, 0)
        mutableOutgoing.value = share
        host.started(share)
        job = scope.launch {
            // Stopping cancels this job, so it runs in its own coroutine.
            launch {
                delay(((end - now) * 1000).coerceAtLeast(0))
                scope.launch { stop() }
            }
            var lastSentAt = 0L
            var lastSent: Fix? = null
            location.updates(minIntervalMillis / 2).collect { fix ->
                if (now >= end) {
                    scope.launch { stop() }
                    return@collect
                }
                val moved = lastSent?.let { GeoMath.distanceMeters(it.position, fix.position) } ?: Double.MAX_VALUE
                if (nowMillis() - lastSentAt < minIntervalMillis && moved < 25.0) return@collect
                sendPoint(session, fix)
                lastSentAt = nowMillis()
                lastSent = fix
            }
        }
        share
    }

    private fun sendPoint(session: KeyPair, fix: Fix) {
        val share = mutableOutgoing.value ?: return
        val seq = share.seq + 1
        val rumor = LiveShares.pointRumor(session, share.shareId, seq, share.end, fix.position, fix.accuracyMeters, now)
        LiveShares.pointWraps(session, share.recipients, rumor).forEach { pool.publish(it) }
        mutableOutgoing.value = share.copy(seq = seq, lastFix = fix, sent = share.sent + 1)
    }

    /** Stops our share now and tells everyone (ephemeral, and stored until the share's end for late phones). */
    suspend fun stop() = lock.withLock { stopLocked() }

    private suspend fun stopLocked() {
        val share = mutableOutgoing.value ?: return
        mutableOutgoing.value = null
        job?.cancel()
        job = null
        if (now < share.end) {
            val session = keyRing().session(share.shareId)
            val rumor = LiveShares.pointRumor(session, share.shareId, share.seq + 1, share.end, null, null, now, stopped = true)
            LiveShares.pointWraps(session, share.recipients, rumor).forEach { pool.publish(it) }
            share.recipients.forEach { to ->
                outbox.enqueue(GiftWrap.wrap(GiftWrap.seal(rumor, session, to), to, extraTags = listOf(listOf("expiration", share.end.toString()))), Priority.MESSAGE)
            }
        }
        host.stopped()
    }
}

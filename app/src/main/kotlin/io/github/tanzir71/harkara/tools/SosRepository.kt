package io.github.tanzir71.harkara.tools

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.SosPolicy
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.tools.SosEvents
import io.github.tanzir71.harkara.core.tools.SosStatus
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RoomEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An SOS from someone else, with how far away it is. */
data class IncomingSos(val event: Event, val distanceMeters: Double?)

/**
 * SOS: kind 3477 at top priority on every route, one per 30 minutes per key, active until the sender
 * resolves it (kind 3478). Incoming SOS within 10 km are shown with "Call 999" first.
 */
class SosRepository(
    private val db: HarkaraDatabase,
    private val store: RoomEventStore,
    private val outbox: Outbox,
    private val pool: RelayPool,
    private val profile: () -> KeyPair,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val now get() = nowMillis() / 1000
    private val sendMutex = Mutex()

    /** All SOS-related events (ours and others'), newest first. */
    private fun events(): Flow<List<Event>> = db.eventDao().observeKinds(listOf(Kinds.SOS, Kinds.SOS_UPDATE)).map { rows -> rows.map { Event.fromJson(it.json) } }

    fun activeOwn(): Flow<Event?> = events().map { SosEvents.active(it, profile().pubkeyHex) }

    fun incoming(center: LatLng): Flow<List<IncomingSos>> = events().map { all ->
        val me = profile().pubkeyHex
        val policy = SosPolicy()
        val alerts = all.filter { it.kind == Kinds.SOS }.associateBy { it.id }
        val closed = all.filter { u -> alerts[u.tagValue("e")]?.let { SosEvents.closes(u, it) } == true }
            .mapNotNull { it.tagValue("e") }.toSet()
        all.sortedBy { it.createdAt }
            .filter { policy.accept(it) }
            .filter { it.kind == Kinds.SOS && it.pubkey != me && it.id !in closed && now - it.createdAt in 0 until 24 * 3600 }
            .map { e ->
                val g = e.tagValues("g").filter { it.length <= 12 && GeoHash.isValid(it) }.maxByOrNull { it.length }
                IncomingSos(e, g?.let { GeoMath.distanceMeters(center, GeoMath.decode(it)) })
            }
            .filter { it.distanceMeters == null || it.distanceMeters < 10_000 }
            .sortedByDescending { it.event.createdAt }
    }

    /** Minutes until another SOS may be sent (0 = now). */
    suspend fun waitMinutes(): Int {
        val mine = db.eventDao().byKinds(listOf(Kinds.SOS, Kinds.SOS_UPDATE)).map { Event.fromJson(it.json) }.filter { it.pubkey == profile().pubkeyHex }
        val last = mine.filter { it.kind == Kinds.SOS }.maxByOrNull { it.createdAt } ?: return 0
        val resolved = mine.any { SosEvents.closes(it, last) }
        return ((SosEvents.waitSeconds(last.createdAt, now, resolved) + 59) / 60).toInt()
    }

    /** Sends an SOS on every open route. Returns null when the 30-minute limit applies. */
    suspend fun send(message: String, at: LatLng?, name: String?): Event? = sendMutex.withLock {
        if (waitMinutes() > 0) return@withLock null
        val event = SosEvents.sos(profile(), at?.lat, at?.lng, message, now, name)
        store.store(event, Carrier.SAVED, own = true)
        outbox.enqueue(event, Priority.SOS)
        event
    }

    suspend fun resolve(sos: Event, status: SosStatus) {
        val event = SosEvents.update(profile(), sos, status, now)
        store.store(event, Carrier.SAVED, own = true)
        outbox.enqueue(event, Priority.SOS)
    }

    fun subscribe(center: LatLng) {
        val cells = GeoMath.withNeighbours(GeoHash.encode(center.lat, center.lng, 5))
        pool.subscribe(SUB_SOS, listOf(Filter(kinds = listOf(Kinds.SOS), tags = mapOf("g" to cells), since = now - 24 * 3600), Filter(kinds = listOf(Kinds.SOS_UPDATE), since = now - 24 * 3600)))
    }

    fun startIngest(scope: CoroutineScope) {
        scope.launch {
            pool.incoming.collect { e -> if (e.event.kind == Kinds.SOS || e.event.kind == Kinds.SOS_UPDATE) store.store(e.event, Carrier.ONLINE, own = false) }
        }
    }

    companion object {
        const val SUB_SOS = "harkara-sos"
    }
}

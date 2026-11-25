package io.github.tanzir71.harkara.relay

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.data.EventEntity
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.OutboxEntity
import io.github.tanzir71.harkara.data.RelayAckEntity
import io.github.tanzir71.harkara.ui.components.Delivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Priority order on every carrier: SOS > messages > tasks/IOUs > posts > reactions. */
object Priority {
    const val SOS = 50
    const val MESSAGE = 40
    const val TASK = 30
    const val POST = 20
    const val REACTION = 10
}

/** Validates and stores events. Every carrier hands events to this one door. */
class RoomEventStore(private val db: HarkaraDatabase, private val now: () -> Long = System::currentTimeMillis) {
    /** Stores [event] if it is valid and new. Returns true when it was newly stored. */
    suspend fun store(event: Event, carrier: Carrier, own: Boolean): Boolean {
        if (!event.validate()) return false
        if (event.isExpired(now() / 1000)) return false
        val geohashes = event.tagValues("g")
        val json = event.toJson()
        return db.eventDao().insert(
            EventEntity(
                id = event.id,
                pubkey = event.pubkey,
                kind = event.kind,
                createdAt = event.createdAt,
                json = json,
                geohash = geohashes.maxByOrNull { it.length },
                geohash5 = geohashes.firstOrNull { it.length == 5 } ?: geohashes.maxByOrNull { it.length }?.take(5),
                category = event.tagValue("t"),
                target = event.tagValue("e"),
                expiresAt = event.expiration,
                receivedAt = now(),
                bytes = json.encodeToByteArray().size,
                own = own,
                carrier = carrier.name,
            ),
        ) != -1L
    }
}

/**
 * Our events on their way out. Saved → Left this phone (handed to a relay socket) → Reached the network
 * (a relay said `OK`). Unacknowledged events are retried with backoff whenever a relay connects.
 */
class Outbox(
    private val db: HarkaraDatabase,
    private val pool: RelayPool,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val waiting: Flow<Int> = db.outboxDao().observeWaiting()

    fun deliveryOf(eventId: String): Flow<Delivery?> =
        db.outboxDao().observeAll().map { rows -> rows.firstOrNull { it.eventId == eventId }?.let { Delivery.valueOf(it.state) } }.distinctUntilChanged()

    suspend fun enqueue(event: Event, priority: Int) {
        db.outboxDao().upsert(
            OutboxEntity(event.id, event.toJson(), priority, attempts = 0, nextAttemptAt = 0, state = Delivery.Saved.name, carrier = Carrier.SAVED.name, createdAt = now()),
        )
        flush()
    }

    /** Sends everything due to the connected relays. */
    suspend fun flush() {
        if (pool.connectedCount == 0) return
        for (row in db.outboxDao().due(now())) {
            val event = Event.fromJson(row.json)
            val left = pool.publish(event)
            if (left > 0) {
                val attempts = row.attempts + 1
                val retryIn = (30_000L shl attempts.coerceAtMost(8)).coerceAtMost(30 * 60_000L)
                db.outboxDao().upsert(
                    row.copy(attempts = attempts, nextAttemptAt = now() + retryIn, state = Delivery.LeftPhone.name, carrier = Carrier.ONLINE.name),
                )
            }
        }
    }

    /** Listens for relay `OK`s and reconnects. Call once with a long-lived scope. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            pool.acks.collect { ack ->
                db.relayAckDao().upsert(RelayAckEntity(ack.eventId, ack.relayUrl, ack.accepted, ack.message, now()))
                val row = db.outboxDao().get(ack.eventId) ?: return@collect
                if (ack.accepted && Delivery.valueOf(row.state).ordinal < Delivery.ReachedNetwork.ordinal) {
                    db.outboxDao().setState(ack.eventId, Delivery.ReachedNetwork.name, Carrier.ONLINE.name)
                }
            }
        }
        scope.launch {
            pool.status.map { s -> s.values.count { it == RelayStatus.CONNECTED } }.distinctUntilChanged().collect { connected ->
                if (connected > 0) flush()
            }
        }
    }
}

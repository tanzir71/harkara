package io.github.tanzir71.harkara.core.relay

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds

/**
 * A tiny NIP-01 relay with no I/O of its own: the LAN room, the neighbourhood node and tests feed it client
 * text and deliver what it returns. Stores valid, unexpired, non-ephemeral events up to [maxEvents]
 * (oldest dropped first), answers REQ with stored matches then EOSE, and pushes new matches live.
 * Ephemeral kinds (20000–29999) are forwarded but never stored.
 */
class LocalRelay(
    private val maxEvents: Int = 5_000,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val accept: (Event) -> Boolean = { true },
) {
    /** A message to send to one connected client. */
    data class Outgoing(val clientId: String, val text: String)

    private val events = LinkedHashMap<String, Event>()
    private val subscriptions = HashMap<String, MutableMap<String, List<Filter>>>()

    val storedCount: Int @Synchronized get() = events.size

    @Synchronized
    fun stored(): List<Event> = events.values.toList()

    @Synchronized
    fun connect(clientId: String) {
        subscriptions.getOrPut(clientId) { HashMap() }
    }

    @Synchronized
    fun disconnect(clientId: String) {
        subscriptions.remove(clientId)
    }

    /** Handles one text frame from [clientId] and returns the frames to send (to it and to others). */
    @Synchronized
    fun handle(clientId: String, text: String): List<Outgoing> {
        val subs = subscriptions.getOrPut(clientId) { HashMap() }
        return when (val msg = ClientMessage.parse(text)) {
            is ClientMessage.Publish -> publish(clientId, msg.event)
            is ClientMessage.Req -> {
                subs[msg.subscriptionId] = msg.filters
                val now = nowSeconds()
                val limit = msg.filters.mapNotNull { it.limit }.maxOrNull() ?: Int.MAX_VALUE
                events.values.asSequence()
                    .filter { e -> !e.isExpired(now) && msg.filters.any { it.matches(e) } }
                    .sortedByDescending { it.createdAt }
                    .take(limit)
                    .map { Outgoing(clientId, RelayMessage.EventMessage(msg.subscriptionId, it).toJson()) }
                    .toList() + Outgoing(clientId, RelayMessage.Eose(msg.subscriptionId).toJson())
            }
            is ClientMessage.Close -> {
                subs.remove(msg.subscriptionId)
                listOf(Outgoing(clientId, RelayMessage.Closed(msg.subscriptionId, "").toJson()))
            }
            null -> listOf(Outgoing(clientId, RelayMessage.Notice("error: could not parse message").toJson()))
        }
    }

    /** Adds an event from this phone itself (no client); returns frames for subscribers. */
    @Synchronized
    fun inject(event: Event): List<Outgoing> = publish(null, event).filter { it.clientId != SELF }

    private fun publish(clientId: String?, event: Event): List<Outgoing> {
        val sender = clientId ?: SELF
        val ok = event.validate() && !event.isExpired(nowSeconds()) && accept(event)
        val out = mutableListOf(Outgoing(sender, RelayMessage.Ok(event.id, ok, if (ok) "" else "invalid: rejected").toJson()))
        if (!ok || events.containsKey(event.id)) return out
        if (!Kinds.isEphemeral(event.kind)) {
            events[event.id] = event
            while (events.size > maxEvents) events.remove(events.keys.first())
        }
        for ((client, subs) in subscriptions) {
            for ((subId, filters) in subs) {
                if (filters.any { it.matches(event) }) out += Outgoing(client, RelayMessage.EventMessage(subId, event).toJson())
            }
        }
        return out
    }

    companion object {
        private const val SELF = "\u0000self"
    }
}

package io.github.tanzir71.harkara.core.store

import io.github.tanzir71.harkara.core.nostr.Event

/** What a bridge needs from an internet relay. The OkHttp relay pool implements this in M4. */
interface RelayClient {
    /** Publishes events (already signed; never re-signed). */
    suspend fun publish(events: List<Event>)

    /** Events tagged under [prefixes] with `created_at` after [since] (unix seconds). */
    suspend fun fetch(prefixes: Set<String>, since: Long): List<Event>
}

/**
 * A phone with data lifting what it carries onto the internet and bringing relay events back to the mesh.
 * Events pass through untouched: same id, same signature.
 */
class BridgeEngine(
    private val store: EventStore,
    private val relay: RelayClient,
    private val subscribedPrefixes: Set<String>,
) {
    private var lastSync: Long = 0

    suspend fun sync(nowSeconds: Long) {
        val digest = store.digest(nowSeconds)
        relay.publish(store.pull(digest.eventIds))
        val downloaded = relay.fetch(subscribedPrefixes, lastSync).filter { it.validate() }
        store.ingestAll(downloaded, nowSeconds)
        lastSync = nowSeconds
    }
}

/** In-memory relay for JVM tests: keeps valid events, answers by `g` prefix and time. */
class InMemoryRelayClient : RelayClient {
    private val events = linkedMapOf<String, Event>()

    val stored: List<Event>
        get() = events.values.toList()

    override suspend fun publish(events: List<Event>) {
        events.filter { it.validate() }.forEach { this.events[it.id] = it }
    }

    override suspend fun fetch(prefixes: Set<String>, since: Long): List<Event> =
        events.values
            .filter { e -> e.createdAt > since && e.tagValues("g").any { g -> prefixes.any { g.startsWith(it) } } }
            .sortedBy { it.createdAt }
}

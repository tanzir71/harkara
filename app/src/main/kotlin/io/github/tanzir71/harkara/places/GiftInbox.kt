package io.github.tanzir71.harkara.places

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opens gift wraps addressed to us once and hands each rumor to the place features that want its kind
 * (pins, check-ins, live location). Wraps are deduplicated by id, since several subscriptions and relays
 * deliver the same one.
 */
class GiftInbox(
    private val profile: () -> KeyPair,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Handler(val kinds: Set<Int>, val block: suspend (Rumor, Event) -> Unit)

    private val handlers = CopyOnWriteArrayList<Handler>()
    private val seen = LinkedHashSet<String>()

    fun register(kinds: Set<Int>, block: suspend (Rumor, Event) -> Unit) {
        handlers += Handler(kinds, block)
    }

    fun start(scope: CoroutineScope, pool: RelayPool) {
        scope.launch { pool.incoming.collect { offer(it.event) } }
    }

    /** Gift wraps (stored) since a week ago and live ephemeral wraps from now on. */
    fun subscribe(pool: RelayPool) {
        val me = profile().pubkeyHex
        val now = nowMillis() / 1000
        pool.subscribe(
            SUB,
            listOf(
                Filter(kinds = listOf(Kinds.GIFT_WRAP), tags = mapOf("p" to listOf(me)), since = now - 7 * 86_400),
                Filter(kinds = listOf(Kinds.EPHEMERAL_GIFT_WRAP), tags = mapOf("p" to listOf(me)), since = now - 120),
            ),
        )
    }

    /** Opens [wrap] if it is for us and new; returns the rumor when a handler took its kind. */
    suspend fun offer(wrap: Event): Rumor? {
        if (wrap.kind != Kinds.GIFT_WRAP && wrap.kind != Kinds.EPHEMERAL_GIFT_WRAP) return null
        val keys = profile()
        if (keys.pubkeyHex !in wrap.tagValues("p")) return null
        synchronized(seen) {
            if (!seen.add(wrap.id)) return null
            if (seen.size > 4096) seen.remove(seen.first())
        }
        val rumor = runCatching { GiftWrap.unwrap(wrap, keys) }.getOrNull() ?: return null
        val wanted = handlers.filter { rumor.kind in it.kinds }
        wanted.forEach { runCatching { it.block(rumor, wrap) } }
        return rumor.takeIf { wanted.isNotEmpty() }
    }

    companion object {
        const val SUB = "harkara-places-inbox"
    }
}

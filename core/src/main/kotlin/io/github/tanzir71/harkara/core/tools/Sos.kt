package io.github.tanzir71.harkara.core.tools

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent

/** How an SOS ends. */
enum class SosStatus(val wire: String) { RESOLVED("resolved"), FALSE_ALARM("false_alarm") }

/**
 * SOS events (kind 3477) and their follow-ups (kind 3478). Top relay priority everywhere; one per 30
 * minutes per key; active until a follow-up resolves it.
 */
object SosEvents {
    const val COOLDOWN_SECONDS = 30 * 60L

    fun sos(keys: KeyPair, latitude: Double?, longitude: Double?, message: String, at: Long, name: String? = null): Event {
        val tags = buildList {
            add(listOf("t", "sos"))
            if (latitude != null && longitude != null) listOf(7, 6, 5).forEach { add(listOf("g", GeoHash.encode(latitude, longitude, it))) }
            name?.takeIf { it.isNotBlank() }?.let { add(listOf("name", it.take(40))) }
        }
        return keys.signEvent(Kinds.SOS, tags, message.take(280), at)
    }

    fun update(keys: KeyPair, sos: Event, status: SosStatus, at: Long): Event {
        require(sos.pubkey == keys.pubkeyHex) { "only the sender closes their SOS" }
        return keys.signEvent(Kinds.SOS_UPDATE, listOf(listOf("e", sos.id), listOf("status", status.wire)), "", at)
    }

    /** Seconds until this key may raise another SOS (0 = now), given its last SOS time. */
    fun waitSeconds(lastSosAt: Long?, now: Long, resolvedSince: Boolean): Long =
        if (lastSosAt == null || resolvedSince) 0 else (lastSosAt + COOLDOWN_SECONDS - now).coerceAtLeast(0)

    /** The SOS still open among [events] for [pubkey], if any. */
    fun active(events: List<Event>, pubkey: String): Event? {
        val mine = events.filter { it.pubkey == pubkey && it.verify() }
        val closed = mine.filter { it.kind == Kinds.SOS_UPDATE }.mapNotNull { it.tagValue("e") }.toSet()
        return mine.filter { it.kind == Kinds.SOS && it.id !in closed }.maxByOrNull { it.createdAt }
    }
}

package io.github.tanzir71.harkara.core

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent

/** Deterministic keys and events for tests. */
object TestData {
    /** 2026-11-25T06:00:00Z, the fake mesh's default clock. */
    const val NOW: Long = 1_795_586_400L

    /** A valid key whose 32 private-key bytes are all [n] (1..255). */
    fun keys(n: Int): KeyPair = KeyPair(ByteArray(32) { n.toByte() })

    fun resource(name: String): String =
        TestData::class.java.classLoader.getResource(name)?.readText() ?: error("missing test resource $name")

    fun post(
        keys: KeyPair,
        text: String = "Gridlocked from Science Lab to Kalabagan",
        geohash: String = "wh0r0d",
        createdAt: Long = NOW,
        category: String = "traffic",
        expiresAt: Long? = createdAt + 3 * 3600,
        kind: Int = Kinds.FEED_POST,
    ): Event = keys.signEvent(
        kind = kind,
        tags = buildList {
            add(listOf("g", geohash))
            add(listOf("t", category))
            if (expiresAt != null) add(listOf("expiration", expiresAt.toString()))
        },
        content = text,
        createdAt = createdAt,
    )
}

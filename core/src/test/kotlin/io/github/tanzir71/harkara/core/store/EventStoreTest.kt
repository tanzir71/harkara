package io.github.tanzir71.harkara.core.store

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.TestData.NOW
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Kinds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventStoreTest {
    private fun post(n: Int, geohash: String, createdAt: Long, expiresAt: Long? = createdAt + 3600) =
        TestData.post(TestData.keys(n), text = "post $n", geohash = geohash, createdAt = createdAt, expiresAt = expiresAt)

    @Test
    fun `feed query returns nearby unexpired posts newest first`() {
        val store = EventStore(subscribedPrefixes = setOf("wh0r"))
        val old = post(1, "wh0r0d", NOW - 50)
        val fresh = post(2, "wh0r3q", NOW)
        val foreign = post(3, "w5cr2b", NOW + 1)
        val expired = post(4, "wh0r0e", NOW - 7200, expiresAt = NOW - 1)
        listOf(old, fresh, foreign, expired).forEach { store.ingest(it, NOW - 7200) }

        assertEquals(listOf(fresh, old), store.query("wh0r", NOW))
        assertEquals(listOf(old), store.query("wh0r0", NOW))
        assertEquals(1, store.purgeExpired(NOW))
    }

    @Test
    fun `foreign areas relay but are not kept, expired ones do neither`() {
        val store = EventStore(subscribedPrefixes = setOf("wh0r"))

        assertEquals(StoreResult(shouldRelay = true, cached = false), store.ingest(post(1, "w5cr2b", NOW), NOW))
        assertEquals(StoreResult(shouldRelay = true, cached = true), store.ingest(post(2, "wh0r0d", NOW), NOW))
        assertEquals(StoreResult(shouldRelay = false, cached = false), store.ingest(post(3, "wh0r0d", NOW - 100, NOW - 1), NOW))
    }

    @Test
    fun `gift wraps are carried for others`() {
        val wrap = GiftWrap.sealAndWrap(GiftWrap.createRumor(TestData.keys(1).pubkeyHex, Kinds.CHAT_MESSAGE, "x"), TestData.keys(1), TestData.keys(2).pubkeyHex)
        assertTrue(EventStore(setOf("wh0r")).ingest(wrap, NOW).cached)
        assertFalse(EventStore(setOf("wh0r"), carryGiftWraps = false).ingest(wrap, NOW).cached)
    }

    @Test
    fun `partition heal sync moves posts by digest and pull`() {
        val alice = EventStore(setOf("wh0r"))
        val bob = EventStore(setOf("wh0r"))
        val p1 = post(1, "wh0r0d", NOW)
        alice.ingest(p1, NOW)

        val wanted = bob.wantedFrom(alice.digest(NOW))
        bob.ingestAll(alice.pull(wanted), NOW)

        assertEquals(setOf(p1.id), wanted)
        assertEquals(listOf(p1), bob.query("wh0r", NOW))
        assertTrue(bob.wantedFrom(alice.digest(NOW)).isEmpty())
    }

    @Test
    fun `oldest events are evicted beyond capacity`() {
        val store = EventStore(setOf(""), capacity = 2)
        val posts = (1..3).map { post(it, "wh0r0d", NOW + it) }
        posts.forEach { store.ingest(it, NOW) }
        assertEquals(listOf(posts[2], posts[1]), store.query("", NOW))
    }

    @Test
    fun `mesh-only post reaches an internet-only phone through a bridge, unchanged`() = runTest {
        val relay = InMemoryRelayClient()
        val bridgePhone = EventStore(setOf("wh0r"))
        val internetPhone = EventStore(setOf("wh0r"))
        val post = post(5, "wh0r0d", NOW)
        bridgePhone.ingest(post, NOW)

        BridgeEngine(bridgePhone, relay, setOf("wh0r")).sync(NOW)
        BridgeEngine(internetPhone, relay, setOf("wh0r")).sync(NOW)

        assertEquals(listOf(post), internetPhone.query("wh0r", NOW))
        assertEquals(post.sig, relay.stored.single().sig)
    }

    @Test
    fun `relay refuses forged events`() = runTest {
        val relay = InMemoryRelayClient()
        relay.publish(listOf(post(6, "wh0r0d", NOW).copy(content = "forged")))
        assertTrue(relay.stored.isEmpty())
    }
}

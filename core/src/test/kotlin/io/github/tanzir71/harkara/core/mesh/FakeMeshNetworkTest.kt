package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** The Nearcast mesh gates, now on signed events. */
class FakeMeshNetworkTest {
    private fun line(network: FakeMeshNetwork, n: Int): List<FakeMeshNode> {
        val nodes = (0 until n).map { network.addNode("n$it") }
        (0 until n - 1).forEach { network.connect(nodes[it], nodes[it + 1]) }
        network.drain()
        return nodes
    }

    @Test
    fun `three hop relay delivers a signed post across a line`() {
        val network = FakeMeshNetwork()
        val nodes = line(network, 4)
        val post = TestData.post(TestData.keys(1))

        nodes[0].broadcast(post)
        network.drain()

        assertEquals(listOf(post), nodes[3].received)
        assertEquals(3, nodes[3].hops[post.id])
        assertTrue(nodes[3].received.single().verify())
    }

    @Test
    fun `hop count stops at the ttl`() {
        val network = FakeMeshNetwork()
        val nodes = line(network, 10)
        val post = TestData.post(TestData.keys(2))

        nodes[0].broadcast(post, ttl = 7)
        network.drain()

        assertEquals(1, nodes[7].received.size, "7 hops away")
        assertEquals(0, nodes[8].received.size, "8 hops away")
    }

    @Test
    fun `dedupe storm in a dense topology stores one copy per node`() {
        val network = FakeMeshNetwork()
        val nodes = (0 until 6).map { network.addNode("n$it") }
        nodes.indices.forEach { a -> (a + 1 until nodes.size).forEach { b -> network.connect(nodes[a], nodes[b]) } }
        network.drain()
        val post = TestData.post(TestData.keys(3))

        nodes[0].broadcast(post)
        network.drain()

        nodes.drop(1).forEach { node ->
            assertEquals(1, node.received.count { it.id == post.id }, node.name)
            assertTrue((node.drops[DropReason.DUPLICATE] ?: 0) > 0, "${node.name} saw duplicates and dropped them")
        }
    }

    @Test
    fun `random twenty node topology delivers to at least ninety five percent`() {
        val random = Random(8)
        val network = FakeMeshNetwork()
        val nodes = (0 until 20).map { network.addNode("n$it") }
        (0 until nodes.lastIndex).forEach { network.connect(nodes[it], nodes[it + 1]) }
        repeat(30) {
            val a = random.nextInt(nodes.size)
            val b = random.nextInt(nodes.size)
            if (a != b) network.connect(nodes[a], nodes[b])
        }
        network.drain()
        val posts = (1..5).map { TestData.post(TestData.keys(40 + it), text = "post $it") }

        posts.forEachIndexed { i, post -> nodes[i * 4].broadcast(post) }
        network.drain()

        posts.forEachIndexed { i, post ->
            val others = nodes.filterIndexed { index, _ -> index != i * 4 }
            val delivered = others.count { node -> node.received.any { it.id == post.id } }
            assertTrue(delivered.toDouble() / others.size >= 0.95, "post $i delivered=$delivered/${others.size}")
        }
    }

    @Test
    fun `events bigger than the mtu are fragmented and reassembled at every hop`() {
        val network = FakeMeshNetwork(mtu = 100)
        val nodes = line(network, 4)
        val text = (1..60).joinToString(" ") { "word$it-${it * 7919 % 1000}" }
        val post = TestData.post(TestData.keys(4), text = text)

        nodes[0].broadcast(post)
        network.drain()

        assertEquals(listOf(post), nodes[3].received)
        assertTrue(nodes.all { it.pendingFragments == 0 })
    }

    @Test
    fun `partition heals by digest and pull sync`() {
        val network = FakeMeshNetwork()
        val left = (0 until 3).map { network.addNode("left$it", subscribedPrefixes = setOf("wh0r")) }
        val right = (0 until 3).map { network.addNode("right$it", subscribedPrefixes = setOf("wh0r")) }
        (0 until 2).forEach { network.connect(left[it], left[it + 1]); network.connect(right[it], right[it + 1]) }
        network.drain()
        val leftPosts = (1..3).map { TestData.post(TestData.keys(50 + it), text = "left $it", geohash = "wh0r0d") }
        val rightPost = TestData.post(TestData.keys(60), text = "right", geohash = "wh0r3q")
        val foreign = TestData.post(TestData.keys(61), text = "chittagong", geohash = "w5cr2b")
        leftPosts.forEach { left[0].broadcast(it) }
        left[0].broadcast(foreign)
        right[2].broadcast(rightPost)
        network.drain()
        assertTrue(right.none { node -> node.received.any { it in leftPosts } })

        network.connect(left[2], right[0])
        network.drain()

        right.forEach { node -> assertTrue(node.received.containsAll(leftPosts), node.name) }
        left.forEach { node -> assertTrue(node.received.contains(rightPost), node.name) }
        right.forEach { node -> assertTrue(node.received.none { it.id == foreign.id }, "foreign areas are relayed live but not stored for sync") }
    }

    @Test
    fun `forged and expired events are dropped and never relayed`() {
        val network = FakeMeshNetwork()
        val nodes = line(network, 3)
        val post = TestData.post(TestData.keys(5))
        val forged = post.copy(content = "Free fuel at the depot")
        val expired = TestData.post(TestData.keys(6), createdAt = TestData.NOW - 4 * 3600, expiresAt = TestData.NOW - 60)

        nodes[0].broadcast(forged)
        nodes[0].broadcast(expired)
        network.drain()

        assertEquals(emptyList<Any>(), nodes[1].received)
        assertEquals(emptyList<Any>(), nodes[2].received)
        assertEquals(1, nodes[1].drops[DropReason.INVALID])
        assertEquals(1, nodes[1].drops[DropReason.EXPIRED])
    }

    @Test
    fun `peers enforce post rate limits before relaying`() {
        val network = FakeMeshNetwork()
        val nodes = line(network, 3)
        val spammer = TestData.keys(7)
        val burst = (0 until 5).map { TestData.post(spammer, text = "spam $it", createdAt = TestData.NOW + it * 10) }

        burst.forEach { nodes[0].broadcast(it) }
        network.drain()

        assertEquals(listOf(burst[0]), nodes[1].received)
        assertEquals(listOf(burst[0]), nodes[2].received)
        assertEquals(4, nodes[1].drops[DropReason.RATE_LIMITED])
    }

    @Test
    fun `sos is limited to one per thirty minutes per key until resolved`() {
        val network = FakeMeshNetwork()
        val nodes = line(network, 2)
        val person = TestData.keys(8)
        val sos = person.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0d")), "Trapped on the 3rd floor", TestData.NOW)
        val again = person.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0d")), "Still trapped", TestData.NOW + 300)
        val resolved = person.signEvent(Kinds.SOS_UPDATE, listOf(listOf("e", sos.id), listOf("status", "resolved")), "Safe now", TestData.NOW + 600)
        val later = person.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0d")), "New emergency", TestData.NOW + 800)

        listOf(sos, again, resolved, later).forEach { nodes[0].broadcast(it) }
        network.drain()

        assertEquals(listOf(sos, resolved, later), nodes[1].received)
        assertEquals(1, nodes[1].drops[DropReason.SOS_LIMITED])
    }

    @Test
    fun `gift wraps cross the mesh opaque and open only at the recipient`() {
        val network = FakeMeshNetwork()
        val nodes = line(network, 4)
        val alice = TestData.keys(9)
        val bob = TestData.keys(10)
        val rumor = GiftWrap.chatMessage(alice.pubkeyHex, listOf(bob.pubkeyHex), "Pharmacy on Road 8 has insulin", createdAt = TestData.NOW)
        val wrap = GiftWrap.wrap(GiftWrap.seal(rumor, alice, bob.pubkeyHex, createdAt = TestData.NOW - 4000), bob.pubkeyHex, createdAt = TestData.NOW - 9000)

        nodes[0].broadcast(wrap)
        network.drain()

        val arrived = nodes[3].received.single()
        assertEquals(Kinds.GIFT_WRAP, arrived.kind)
        assertEquals("Pharmacy on Road 8 has insulin", GiftWrap.unwrap(arrived, bob).content)
        assertTrue(nodes[1].store.contains(wrap.id), "carried for store-and-forward")
    }
}

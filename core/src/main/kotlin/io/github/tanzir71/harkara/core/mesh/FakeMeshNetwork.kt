package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.store.EventStore
import io.github.tanzir71.harkara.core.store.SyncDigest
import io.github.tanzir71.harkara.core.wire.FrameType
import io.github.tanzir71.harkara.core.wire.MeshFrame
import java.time.Instant

/**
 * Deterministic in-memory mesh for protocol tests. Nodes exchange encoded [MeshFrame] bytes over links
 * with an MTU (fragmenting as needed), run the real [MeshRelay] rules, and sync by digest/pull when a link
 * comes up.
 */
class FakeMeshNetwork(
    /** Link MTU in bytes; frames above it are fragmented. */
    var mtu: Int = 512,
    /** Simulated clock shared by all nodes. */
    var now: Instant = Instant.parse("2026-11-25T06:00:00Z"),
) {
    private val nodes = mutableListOf<FakeMeshNode>()
    private val links = mutableMapOf<FakeMeshNode, MutableSet<FakeMeshNode>>()
    private val pending = ArrayDeque<Transmission>()

    /** Total encoded bytes sent over all links (fragments included). */
    var bytesOnAir: Long = 0
        private set

    fun addNode(name: String, subscribedPrefixes: Set<String> = setOf("")): FakeMeshNode {
        val node = FakeMeshNode(name, this, EventStore(subscribedPrefixes))
        nodes += node
        links[node] = mutableSetOf()
        return node
    }

    /** Connects two nodes; with [sync] they swap digests at once, which heals partitions. */
    fun connect(a: FakeMeshNode, b: FakeMeshNode, sync: Boolean = true) {
        require(a in nodes && b in nodes && a !== b) { "both nodes must belong to this network" }
        links.getValue(a) += b
        links.getValue(b) += a
        if (sync) {
            a.sendTo(b, a.digestFrame())
            b.sendTo(a, b.digestFrame())
        }
    }

    fun disconnect(a: FakeMeshNode, b: FakeMeshNode) {
        links.getValue(a) -= b
        links.getValue(b) -= a
    }

    fun neighbours(node: FakeMeshNode): Set<FakeMeshNode> = links.getValue(node).toSet()

    /** Delivers queued frames until the network is idle. */
    fun drain() {
        var processed = 0
        while (pending.isNotEmpty()) {
            check(processed++ < MAX_TRANSMISSIONS) { "fake mesh did not quiesce" }
            val t = pending.removeFirst()
            if (t.to in links.getValue(t.from)) t.to.receive(t.from, t.bytes)
        }
    }

    internal fun transmit(from: FakeMeshNode, to: FakeMeshNode, frame: MeshFrame) {
        Fragmenter.split(frame, mtu).forEach { piece ->
            val bytes = piece.encode()
            bytesOnAir += bytes.size
            pending += Transmission(from, to, bytes)
        }
    }

    private class Transmission(val from: FakeMeshNode, val to: FakeMeshNode, val bytes: ByteArray)

    private companion object {
        const val MAX_TRANSMISSIONS = 1_000_000
    }
}

/** One simulated phone. */
class FakeMeshNode internal constructor(
    val name: String,
    private val network: FakeMeshNetwork,
    val store: EventStore,
) {
    val relay = MeshRelay(store)
    private val reassembler = FragmentReassembler()
    private val delivered = mutableListOf<Event>()
    private val hopsById = mutableMapOf<String, Int>()
    private val dropCounts = mutableMapOf<DropReason, Int>()

    /** Events this node accepted from others, in arrival order. */
    val received: List<Event>
        get() = delivered.toList()

    /** How many hops each received event took to get here. */
    val hops: Map<String, Int>
        get() = hopsById.toMap()

    val drops: Map<DropReason, Int>
        get() = dropCounts.toMap()

    /** Partially received fragment sets. */
    val pendingFragments: Int
        get() = reassembler.pendingSets

    fun broadcast(event: Event, ttl: Int = MeshFrame.DEFAULT_TTL) {
        val frame = relay.originate(event, network.now, ttl)
        network.neighbours(this).forEach { sendTo(it, frame) }
    }

    internal fun digestFrame(): MeshFrame = MeshFrame.digest(store.digest(network.now.epochSecond).eventIds)

    internal fun sendTo(neighbour: FakeMeshNode, frame: MeshFrame) = network.transmit(this, neighbour, frame)

    internal fun receive(from: FakeMeshNode, bytes: ByteArray) {
        val frame = runCatching { MeshFrame.decode(bytes) }.getOrNull() ?: return count(DropReason.MALFORMED)
        val whole = if (frame.type == FrameType.FRAGMENT) reassembler.accept(frame, network.now) ?: return else frame
        when (whole.type) {
            FrameType.EVENT -> onEvent(from, whole)
            FrameType.DIGEST -> {
                val wanted = store.wantedFrom(SyncDigest(whole.ids().toSet())).filterNot { relay.hasSeen(it, network.now) }
                if (wanted.isNotEmpty()) sendTo(from, MeshFrame.pull(wanted))
            }
            FrameType.PULL -> store.pull(whole.ids()).forEach { sendTo(from, MeshFrame.event(it)) }
            FrameType.PRESENCE, FrameType.FRAGMENT -> Unit
        }
    }

    private fun onEvent(from: FakeMeshNode, frame: MeshFrame) {
        when (val decision = relay.onFrame(frame, network.now)) {
            is RelayDecision.Accepted -> {
                delivered += decision.event
                hopsById[decision.event.id] = decision.hops
                decision.relay?.let { next -> network.neighbours(this).filter { it !== from }.forEach { sendTo(it, next) } }
            }
            is RelayDecision.Dropped -> count(decision.reason)
        }
    }

    private fun count(reason: DropReason) {
        dropCounts[reason] = (dropCounts[reason] ?: 0) + 1
    }

    override fun toString(): String = "FakeMeshNode($name)"
}

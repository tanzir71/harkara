package io.github.tanzir71.harkara.core

/** In-memory transport graph for deterministic mesh protocol tests. */
class FakeMeshNetwork {
    private val nodes = mutableSetOf<FakeMeshNode>()
    private val links = mutableMapOf<FakeMeshNode, MutableSet<FakeMeshNode>>()
    private val pending = ArrayDeque<Transmission>()

    /** Adds a simulated node. */
    fun addNode(id: NearId): FakeMeshNode {
        val node = FakeMeshNode(id, this)
        nodes += node
        links[node] = mutableSetOf()
        return node
    }

    /** Connects two simulated nodes bidirectionally. */
    fun connect(a: FakeMeshNode, b: FakeMeshNode) {
        require(a in nodes && b in nodes) { "both nodes must belong to this network" }
        links.getValue(a) += b
        links.getValue(b) += a
    }

    /** Runs queued relay work until the network is idle. */
    fun drain() {
        var processed = 0
        while (pending.isNotEmpty()) {
            check(processed++ < MAX_TRANSMISSIONS) { "fake mesh did not quiesce" }
            val transmission = pending.removeFirst()
            deliver(transmission)
        }
    }

    internal fun broadcast(from: FakeMeshNode, packet: MeshPacket) {
        from.markSeen(packet)
        links.getValue(from).forEach { neighbor ->
            pending += Transmission(from = from, to = neighbor, packet = packet)
        }
    }

    private fun deliver(transmission: Transmission) {
        val receiver = transmission.to
        val packet = transmission.packet
        if (packet.ttl <= 0 || !receiver.markSeen(packet)) return
        receiver.record(packet)
        if (packet.ttl <= 1) return
        val relayed = packet.forRelay()
        links.getValue(receiver)
            .filterNot { it == transmission.from }
            .forEach { neighbor -> pending += Transmission(receiver, neighbor, relayed) }
    }

    private data class Transmission(
        val from: FakeMeshNode,
        val to: FakeMeshNode,
        val packet: MeshPacket,
    )

    private companion object {
        const val MAX_TRANSMISSIONS = 100_000
    }
}

/** Simulated mesh node connected through [FakeMeshNetwork]. */
class FakeMeshNode internal constructor(
    /** Node identity. */
    val id: NearId,
    private val network: FakeMeshNetwork,
) {
    private val dedupe = LruDedupeCache(capacity = 10_000, expiry = java.time.Duration.ofMinutes(30))
    private val delivered = mutableListOf<MeshPacket>()

    /** Packets delivered to this node. */
    val received: List<MeshPacket>
        get() = delivered.toList()

    /** Broadcasts a packet to neighbors. */
    fun broadcast(packet: MeshPacket) {
        network.broadcast(this, packet)
    }

    internal fun markSeen(packet: MeshPacket): Boolean = dedupe.markIfNew(packet.msgId, packet.timestamp)

    internal fun record(packet: MeshPacket) {
        delivered += packet
    }
}

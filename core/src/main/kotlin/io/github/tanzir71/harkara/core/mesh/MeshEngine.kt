package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.store.EventStore
import io.github.tanzir71.harkara.core.store.SyncDigest
import io.github.tanzir71.harkara.core.wire.FrameType
import io.github.tanzir71.harkara.core.wire.MeshFrame
import java.time.Instant

/** A phone in mesh reach, as this phone sees it. */
data class MeshPeer(
    /** `<radio>:<address>`; addresses rotate, so this is not an identity. */
    val id: String,
    val radio: String,
    /** First 4 bytes of the peer's daily presence id, hex; null until it announced itself. */
    val presence: String?,
    val rssi: Int?,
    /** Hops from the peer to the nearest phone with internet (0: the peer has internet itself). */
    val bridgeHops: Int?,
    val node: Boolean,
    /** Frames received from this peer since the link came up (range test). */
    val framesIn: Int,
    val lastSeenMillis: Long,
)

/**
 * The mesh on one phone, over any number of [MeshRadio]s. Applies [MeshRelay]'s rules to every event, relays
 * with one more hop to the other links, fragments to each link's MTU, swaps digests when a link comes up
 * (store-and-forward and partition heal) and announces [PresenceInfo] so phones know how far the nearest
 * bridge is. Thread-safe; [Listener] calls happen under the engine's lock and must return quickly.
 */
class MeshEngine(
    private val store: EventStore,
    private val presenceId: () -> ByteArray,
    private val clock: () -> Instant = Instant::now,
    profile: MeshProfile = MeshProfile.SAVER,
    private val listener: Listener = object : Listener {},
    accept: (Event) -> Boolean = { true },
) {
    interface Listener {
        /** A new, valid event arrived over the mesh, [hops] away. */
        fun onEvent(event: Event, hops: Int, peer: String) = Unit

        /** Peers, their presence or the bridge distance changed. */
        fun onPeersChanged(peers: List<MeshPeer>) = Unit
    }

    private val relay = MeshRelay(store, accept = accept)
    private val radios = mutableListOf<MeshRadio>()
    private val links = LinkedHashMap<String, Link>()
    private val sightings = HashMap<String, Int>()
    private val dropCounts = HashMap<DropReason, Int>()
    private var lastPresenceAt = Instant.EPOCH
    private var announcedBridge: Int? = null
    private var startedAt = clock()

    @Volatile
    var profile: MeshProfile = profile
        private set

    /** This phone reaches the internet relays: it becomes a bridge (bridge hops 0). */
    var hasInternet: Boolean = false
        @Synchronized set(value) {
            if (field == value) return
            field = value
            announce()
        }

    /** This phone runs a neighbourhood node (announced to peers). */
    var node: Boolean = false
        @Synchronized set(value) {
            if (field == value) return
            field = value
            announce()
        }

    /** Hops from this phone to the nearest bridge: 0 with internet, otherwise via the closest linked peer. */
    val bridgeHops: Int?
        @Synchronized get() = computeBridgeHops()

    val linkCount: Int
        @Synchronized get() = links.size

    val drops: Map<DropReason, Int>
        @Synchronized get() = dropCounts.toMap()

    @Synchronized
    fun peers(): List<MeshPeer> = links.values.map { it.snapshot() }

    /** Starts [radio] and lets it carry this engine's frames. */
    fun attach(radio: MeshRadio) {
        synchronized(this) {
            if (radios.isEmpty()) startedAt = clock()
            radios += radio
        }
        radio.start(presenceId().copyOf(LinkPolicy.TOKEN_BYTES), RadioListener(radio))
        radio.setScanning(profile.scanning(0))
    }

    fun stop() {
        val stopping = synchronized(this) { radios.toList().also { radios.clear() } }
        stopping.forEach { runCatching { it.stop() } }
        synchronized(this) {
            links.clear()
            sightings.clear()
            notifyPeers()
        }
    }

    @Synchronized
    fun setProfile(profile: MeshProfile) {
        this.profile = profile
        startedAt = clock()
        radios.forEach { it.setScanning(profile.scanning(0)) }
    }

    /**
     * Sends this phone's own [event] to every link and keeps it for peers who connect later. Returns false
     * when it isn't allowed over the mesh or was already carried.
     */
    @Synchronized
    fun originate(event: Event): Boolean {
        val now = clock()
        if (!MeshPolicy.eligible(event, now.epochSecond) || relay.hasSeen(event.id, now)) return false
        broadcast(relay.originate(event, now, profile.ttl), except = null)
        return true
    }

    /**
     * Keeps an event that came by another carrier (relays, the LAN) so mesh peers can pull it on their next
     * digest; with [push] it also goes to linked peers now (SOS from the internet reaching offline neighbours).
     */
    @Synchronized
    fun carry(event: Event, push: Boolean = false): Boolean {
        val now = clock()
        if (!MeshPolicy.eligible(event, now.epochSecond) || relay.hasSeen(event.id, now)) return false
        val frame = MeshFrame.event(event, profile.ttl)
        val decision = relay.onEvent(event, frame, now)
        if (decision !is RelayDecision.Accepted) return false
        if (push) broadcast(frame, except = null)
        return true
    }

    /** True when the mesh already handled [eventId]. */
    @Synchronized
    fun hasSeen(eventId: String): Boolean = relay.hasSeen(eventId, clock())

    /** Kept events (store-and-forward cache) for a bridge to upload. */
    @Synchronized
    fun carried(): List<Event> = store.pull(store.digest(clock().epochSecond).eventIds)

    /** Housekeeping, called every few seconds: scan duty cycle, presence, expiry. */
    @Synchronized
    fun tick() {
        val now = clock()
        val scanning = profile.scanning(now.epochSecond - startedAt.epochSecond)
        radios.forEach { it.setScanning(scanning) }
        if (now.epochSecond - lastPresenceAt.epochSecond >= profile.presenceSeconds) {
            announce()
            broadcast(digestFrame(), except = null)
        }
        store.purgeExpired(now.epochSecond)
    }

    private fun computeBridgeHops(): Int? {
        if (hasInternet) return 0
        return links.values.mapNotNull { it.bridgeHops }.filter { it + 1 < MAX_BRIDGE_HOPS }.minOrNull()?.plus(1)
    }

    private fun presence(): MeshFrame = PresenceInfo(presenceId(), hasInternet, node, computeBridgeHops()).frame()

    private fun announce(only: Link? = null) {
        val frame = presence()
        if (only != null) {
            sendTo(only, frame)
        } else {
            links.values.forEach { sendTo(it, frame) }
            lastPresenceAt = clock()
        }
        announcedBridge = computeBridgeHops()
    }

    private fun broadcast(frame: MeshFrame, except: Link?) {
        links.values.filter { it !== except }.forEach { sendTo(it, frame) }
    }

    private fun sendTo(link: Link, frame: MeshFrame) {
        val priority = MeshPriority.of(frame)
        Fragmenter.split(frame, link.mtu).forEach { link.radio.send(link.address, it.encode(), priority) }
    }

    private fun digestFrame(): MeshFrame {
        val ids = store.digest(clock().epochSecond).eventIds.toList().takeLast(MAX_DIGEST_IDS)
        return MeshFrame.digest(ids)
    }

    private fun notifyPeers() = listener.onPeersChanged(links.values.map { it.snapshot() })

    @Synchronized
    private fun peerUp(radio: MeshRadio, address: String, mtu: Int) {
        val id = "${radio.kind}:$address"
        val link = Link(id, radio, address, mtu.coerceAtLeast(MIN_MTU)).also { it.rssi = sightings[id] }
        links[id] = link
        link.lastSeen = clock().toEpochMilli()
        announce(only = link)
        sendTo(link, digestFrame())
        notifyPeers()
    }

    @Synchronized
    private fun peerDown(radio: MeshRadio, address: String) {
        if (links.remove("${radio.kind}:$address") == null) return
        if (computeBridgeHops() != announcedBridge) announce()
        notifyPeers()
    }

    @Synchronized
    private fun sighting(radio: MeshRadio, address: String, rssi: Int) {
        val id = "${radio.kind}:$address"
        sightings[id] = rssi
        if (sightings.size > MAX_SIGHTINGS) sightings.remove(sightings.keys.first())
        links[id]?.rssi = rssi
    }

    @Synchronized
    private fun bytes(radio: MeshRadio, address: String, bytes: ByteArray) {
        val link = links["${radio.kind}:$address"] ?: return
        val now = clock()
        link.framesIn++
        link.lastSeen = now.toEpochMilli()
        val frame = runCatching { MeshFrame.decode(bytes) }.getOrNull() ?: return count(DropReason.MALFORMED)
        val whole = if (frame.type == FrameType.FRAGMENT) {
            runCatching { link.reassembler.accept(frame, now) }.getOrNull() ?: return
        } else {
            frame
        }
        runCatching { handle(link, whole, now) }.onFailure { count(DropReason.MALFORMED) }
    }

    private fun handle(link: Link, frame: MeshFrame, now: Instant) {
        when (frame.type) {
            FrameType.EVENT -> when (val decision = relay.onFrame(frame, now)) {
                is RelayDecision.Accepted -> {
                    listener.onEvent(decision.event, decision.hops, link.id)
                    decision.relay?.let { broadcast(it, except = link) }
                }
                is RelayDecision.Dropped -> count(decision.reason)
            }
            FrameType.DIGEST -> {
                val wanted = store.wantedFrom(SyncDigest(frame.ids().toSet())).filterNot { relay.hasSeen(it, now) }
                wanted.chunked(MAX_DIGEST_IDS).forEach { sendTo(link, MeshFrame.pull(it)) }
            }
            FrameType.PULL -> store.pull(frame.ids()).forEach { sendTo(link, MeshFrame.event(it, profile.ttl)) }
            FrameType.PRESENCE -> {
                val info = PresenceInfo.parse(frame)
                link.presence = Hex.encode(info.id.copyOf(4))
                link.bridgeHops = if (info.internet) 0 else info.bridgeHops
                link.node = info.node
                if (computeBridgeHops() != announcedBridge) announce()
                notifyPeers()
            }
            FrameType.FRAGMENT -> Unit
        }
    }

    private fun count(reason: DropReason) {
        dropCounts[reason] = (dropCounts[reason] ?: 0) + 1
    }

    private inner class RadioListener(private val radio: MeshRadio) : MeshRadio.Listener {
        override fun onPeerUp(peer: String, mtu: Int) = peerUp(radio, peer, mtu)

        override fun onPeerDown(peer: String) = peerDown(radio, peer)

        override fun onBytes(peer: String, bytes: ByteArray) = bytes(radio, peer, bytes)

        override fun onSighting(peer: String, rssi: Int) = sighting(radio, peer, rssi)
    }

    private class Link(val id: String, val radio: MeshRadio, val address: String, val mtu: Int) {
        val reassembler = FragmentReassembler()
        var presence: String? = null
        var bridgeHops: Int? = null
        var node = false
        var rssi: Int? = null
        var framesIn = 0
        var lastSeen = 0L

        fun snapshot() = MeshPeer(id, radio.kind, presence, rssi, bridgeHops, node, framesIn, lastSeen)
    }

    companion object {
        /** A bridge further than this is not worth announcing. */
        const val MAX_BRIDGE_HOPS = 7

        /** Digests offer the newest ids only, so a link-up costs at most ~16 KB. */
        const val MAX_DIGEST_IDS = 512

        /** Smallest usable link MTU (BLE default 23 minus ATT overhead is 20; fragments need 24 + data). */
        const val MIN_MTU = 64

        private const val MAX_SIGHTINGS = 256
    }
}

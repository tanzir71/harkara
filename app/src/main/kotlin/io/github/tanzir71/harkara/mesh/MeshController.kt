package io.github.tanzir71.harkara.mesh

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.mesh.MeshEngine
import io.github.tanzir71.harkara.core.mesh.MeshPeer
import io.github.tanzir71.harkara.core.mesh.MeshProfile
import io.github.tanzir71.harkara.core.mesh.MeshRadio
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.store.EventStore
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.OutboxRoute
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RoomEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The Bluetooth mesh on this phone, while Mesh mode (or Disaster mode) is on: a [MeshEngine] over the radios,
 * fed and drained through the same doors as every other carrier.
 *
 * - Events from the mesh are validated into Room (carrier Mesh) and emitted on [incoming] for chats.
 * - Our own waiting events leave through [route] (the outbox hands them over once).
 * - **Bridge**: while this phone reaches a relay, everything it carries for others is uploaded unchanged, and
 *   area posts and SOS from the relays are kept for mesh neighbours (SOS pushed at once).
 */
class MeshController(
    private val scope: CoroutineScope,
    private val db: HarkaraDatabase,
    private val store: RoomEventStore,
    private val pool: RelayPool,
    private val presenceId: () -> ByteArray,
    private val radios: () -> List<MeshRadio>,
    /** Geohash prefix of the area whose events this phone keeps for neighbours. */
    private val areaPrefix: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = 5_000,
    private val ingest: (suspend (Event, Carrier) -> Boolean)? = null,
    private val isNode: () -> Boolean = { false },
    private val accept: (Event) -> Boolean = { true },
) {
    data class Status(
        val running: Boolean = false,
        val profile: MeshProfile = MeshProfile.SAVER,
        val peers: List<MeshPeer> = emptyList(),
        val bridgeHops: Int? = null,
    )

    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private val mutableIncoming = MutableSharedFlow<Event>(extraBufferCapacity = 256)

    /** Valid events that arrived over the mesh. */
    val incoming: SharedFlow<Event> = mutableIncoming.asSharedFlow()

    @Volatile private var engine: MeshEngine? = null
    private val jobs = mutableListOf<Job>()
    private val uploaded = ConcurrentHashMap<String, Long>()

    /** Hands our own events to the mesh; open while at least one phone is linked. */
    val route: OutboxRoute = object : OutboxRoute {
        override val carrier: Carrier = Carrier.MESH
        override val ready: StateFlow<Boolean> = status.map { it.running && it.peers.isNotEmpty() }.stateIn(scope, SharingStarted.Eagerly, false)

        override suspend fun carry(event: Event): Boolean = engine?.originate(event) ?: false
    }

    val running: Boolean get() = engine != null

    /** Starts the mesh, or switches the mode of the running one. */
    @Synchronized
    fun start(profile: MeshProfile) {
        engine?.let {
            it.setProfile(profile)
            publishStatus()
            return
        }
        val engine = MeshEngine(EventStore(setOf(areaPrefix())), presenceId, { Instant.ofEpochMilli(clock()) }, profile, listener, accept)
        this.engine = engine
        mutableStatus.value = Status(running = true, profile = profile)
        jobs += scope.launch { seed(engine) }
        radios().forEach { radio -> runCatching { engine.attach(radio) } }
        jobs += scope.launch {
            while (isActive) {
                runCatching { tick(engine) }
                delay(tickMillis)
            }
        }
        jobs += scope.launch { pool.incoming.collect { runCatching { fromRelay(engine, it.event) } } }
        jobs += scope.launch { pool.acks.collect { ack ->
            if (uploaded.containsKey(ack.eventId)) {
                if (ack.accepted) uploaded[ack.eventId] = Long.MAX_VALUE else uploaded.remove(ack.eventId)
            }
        } }
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val e = engine
        engine = null
        e?.stop()
        uploaded.clear()
        mutableStatus.value = Status()
    }

    /** Runs one housekeeping round now (tests; the loop does it every few seconds). */
    suspend fun tickNow() {
        engine?.let { tick(it) }
    }

    private val listener = object : MeshEngine.Listener {
        override fun onEvent(event: Event, hops: Int, peer: String) {
            scope.launch { received(event) }
        }

        override fun onPeersChanged(peers: List<MeshPeer>) = publishStatus()
    }

    private fun publishStatus() {
        val e = engine ?: return
        mutableStatus.value = Status(running = true, profile = e.profile, peers = e.peers(), bridgeHops = e.bridgeHops)
    }

    private suspend fun received(event: Event) {
        if (ingest != null) { if (!ingest.invoke(event, Carrier.MESH)) return }
        else store.store(event, Carrier.MESH, own = false)
        mutableIncoming.emit(event)
        if (pool.connectedCount > 0) upload(event)
    }

    private fun upload(event: Event) {
        val previous = uploaded[event.id]
        if (previous == Long.MAX_VALUE || (previous != null && clock() - previous < 30_000)) return
        uploaded[event.id] = clock()
        if (pool.publish(event) == 0) uploaded.remove(event.id)
    }

    private fun tick(engine: MeshEngine) {
        engine.hasInternet = pool.connectedCount > 0
        engine.node = isNode()
        engine.tick()
        val carried = engine.carried()
        val ids = carried.mapTo(HashSet()) { it.id }
        uploaded.keys.retainAll(ids)
        if (engine.hasInternet) carried.forEach(::upload)
        publishStatus()
    }

    private fun fromRelay(engine: MeshEngine, event: Event) {
        if (event.kind !in NEIGHBOUR_KINDS) return
        if (event.tagValues("g").none { it.startsWith(areaPrefix()) }) return
        val urgent = event.kind in SOS_KINDS && event.createdAt > clock() / 1000 - 3_600
        engine.carry(event, push = urgent)
    }

    private suspend fun seed(engine: MeshEngine) {
        val prefix = areaPrefix()
        db.eventDao().byKinds((NEIGHBOUR_KINDS + setOf(Kinds.GIFT_WRAP, Kinds.SMS_DM)).toList()).forEach { row ->
            // Our waiting events must originate through Outbox, not be pre-marked as carried here.
            if (row.own) return@forEach
            val event = runCatching { Event.fromJson(row.json) }.getOrNull() ?: return@forEach
            if (event.tagValues("g").any { it.startsWith(prefix) }) engine.carry(event)
        }
    }

    companion object {
        /** Public area events a phone keeps for its mesh neighbours. */
        val NEIGHBOUR_KINDS = setOf(Kinds.FEED_POST, Kinds.SOS, Kinds.SOS_UPDATE, Kinds.CHECK_IN, Kinds.TASK_OFFER, Kinds.TASK_STEP, Kinds.AREA_CHAT, Kinds.REACTION, Kinds.REPORT, Kinds.PIN)
        private val SOS_KINDS = setOf(Kinds.SOS, Kinds.SOS_UPDATE)

        /** Geohash length kept for neighbours (about 39 × 20 km: all of Dhaka in one or two cells). */
        const val AREA_PRECISION = 4
    }
}

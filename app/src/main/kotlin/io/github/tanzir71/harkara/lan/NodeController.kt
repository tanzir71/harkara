package io.github.tanzir71.harkara.lan

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.chat.ChatProtocol
import io.github.tanzir71.harkara.core.lan.NodeSite
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.OutboxRoute
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RoomEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The Wi-Fi room beyond chats:
 * - **Feed sync**: posts, tasks and SOS from neighbourhood nodes in the room are validated into Room
 *   (carrier Node), and our own go to the nodes through [route]. A phone that also reaches a relay uploads
 *   what the room gave it (bridge).
 * - **Node mode** ([startNode]): this phone's room server keeps every event for the room, is seeded with the
 *   area's recent events and serves the download page, APK, map packs and handbook ([NodeSite]).
 * - **Share the app** ([startSharing]): only the download page and APK, while the share screen is open.
 */
class NodeController(
    private val scope: CoroutineScope,
    private val room: LanRoom,
    private val db: HarkaraDatabase,
    private val store: RoomEventStore,
    private val pool: RelayPool,
    private val site: (node: Boolean) -> NodeSite,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ingest: (suspend (Event, Carrier) -> Boolean)? = null,
) {
    private val mutableNode = MutableStateFlow(false)
    private val mutableSharing = MutableStateFlow(false)
    private val carried: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private var syncJob: Job? = null

    /** This phone runs a neighbourhood node. */
    val node: StateFlow<Boolean> = mutableNode.asStateFlow()

    /** The share-the-app page is being served. */
    val sharing: StateFlow<Boolean> = mutableSharing.asStateFlow()

    /** Our events to the nodes in the room; open while one is connected. */
    val route: OutboxRoute = object : OutboxRoute {
        override val carrier: Carrier = Carrier.NODE
        override val ready: StateFlow<Boolean> = room.nodePeers.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

        override suspend fun carry(event: Event): Boolean {
            if (ChatProtocol.carriedByLanRoom(event) || !carried.add(event.id)) return false
            return (room.publishToNodes(event) > 0).also { if (!it) carried.remove(event.id) }
        }
    }

    /** The address people next to this phone open in a browser, or null without Wi-Fi/hotspot. */
    fun address(): String? = LocalAddress.ipv4()?.let { ip -> room.port.takeIf { it > 0 }?.let { "http://$ip:$it/" } }

    /** Starts reading posts from nodes in the room; call once. */
    fun startSync() {
        if (syncJob != null) return
        syncJob = scope.launch { room.incoming.collect { runCatching { fromRoom(it) } } }
        scope.launch {
            room.nodePeers.map { it > 0 }.distinctUntilChanged().collect { any ->
                if (any) room.subscribe(SUB_NODE, listOf(Filter(kinds = SYNC_KINDS, since = clock() / 1000 - 2 * 86_400, limit = 500)))
            }
        }
    }

    @Synchronized
    fun startNode() {
        if (mutableNode.value) return
        room.node = true
        room.http = site(true)
        if (room.running.value) room.restart() else room.start()
        mutableNode.value = true
        startSync()
        scope.launch { seed() }
    }

    /** Stops node mode; the room keeps running as a plain room while [keepRoom] (the app is open). */
    @Synchronized
    fun stopNode(keepRoom: Boolean) {
        if (!mutableNode.value) return
        mutableNode.value = false
        room.node = false
        room.http = if (mutableSharing.value) site(false) else null
        if (keepRoom) room.restart() else room.stop()
    }

    @Synchronized
    fun startSharing() {
        mutableSharing.value = true
        if (!mutableNode.value) room.http = site(false)
        if (!room.running.value) room.start()
    }

    @Synchronized
    fun stopSharing() {
        mutableSharing.value = false
        if (!mutableNode.value) room.http = null
    }

    private suspend fun fromRoom(event: Event) {
        if (ChatProtocol.carriedByLanRoom(event) || event.kind !in SYNC_KINDS) return
        val accepted = ingest?.invoke(event, Carrier.NODE) ?: store.store(event, Carrier.NODE, own = false)
        if (accepted && pool.connectedCount > 0) pool.publish(event)
    }

    private suspend fun seed() {
        db.eventDao().byKinds(SYNC_KINDS).forEach { row ->
            runCatching { Event.fromJson(row.json) }.getOrNull()?.takeUnless { it.isExpired(clock() / 1000) }?.let { room.relay.inject(it) }
        }
    }

    companion object {
        const val SUB_NODE = "node-area"

        /** What a node keeps and hands out besides chats. */
        val SYNC_KINDS = listOf(
            Kinds.FEED_POST, Kinds.REACTION, Kinds.REPORT, Kinds.SOS, Kinds.SOS_UPDATE, Kinds.CHECK_IN,
            Kinds.TASK_OFFER, Kinds.TASK_STEP, Kinds.PIN,
        )
    }
}

/** This phone's address on the Wi-Fi it joined or the hotspot it runs. */
object LocalAddress {
    fun ipv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { nic.name to it.hostAddress } }
        // Hotspot interfaces first (people join this phone), then Wi-Fi, then anything else on the LAN.
        candidates.firstOrNull { (name, _) -> name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") }?.second
            ?: candidates.firstOrNull { (name, _) -> name.startsWith("wlan") }?.second
            ?: candidates.firstOrNull()?.second
    }.getOrNull()
}

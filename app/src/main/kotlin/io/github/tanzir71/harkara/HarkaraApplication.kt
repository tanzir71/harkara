package io.github.tanzir71.harkara

import android.app.Application
import android.content.Context
import io.github.tanzir71.harkara.chat.AndroidVoicePlayer
import io.github.tanzir71.harkara.chat.AndroidVoiceRecorder
import io.github.tanzir71.harkara.chat.ChatRepository
import io.github.tanzir71.harkara.chat.SettingsChatPrefs
import io.github.tanzir71.harkara.chat.VoicePlayer
import io.github.tanzir71.harkara.chat.VoiceRecorder
import io.github.tanzir71.harkara.connectivity.ConnectivityMonitor
import io.github.tanzir71.harkara.core.feed.PostComposer
import io.github.tanzir71.harkara.core.feed.WordFilters
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.keys.IsoWeek
import io.github.tanzir71.harkara.crash.CrashReporter
import io.github.tanzir71.harkara.data.DhakaAreas
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.feed.FeedRepository
import io.github.tanzir71.harkara.identity.FileSeedStore
import io.github.tanzir71.harkara.identity.IdentityManager
import io.github.tanzir71.harkara.lan.LanRoom
import io.github.tanzir71.harkara.lan.NsdLanDiscovery
import io.github.tanzir71.harkara.relay.DefaultRelays
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.relay.LiveConnectivityMonitor
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelaySyncWorker
import io.github.tanzir71.harkara.settings.AppSettings
import io.github.tanzir71.harkara.settings.PanicWipeController
import io.github.tanzir71.harkara.settings.SettingsState
import io.github.tanzir71.harkara.update.UpdateChecker
import io.github.tanzir71.harkara.core.tasks.TaskTypes
import io.github.tanzir71.harkara.tasks.TaskRepository
import io.github.tanzir71.harkara.khata.KhataRepository
import io.github.tanzir71.harkara.tools.CameraTorch
import io.github.tanzir71.harkara.tools.Handbook
import io.github.tanzir71.harkara.tools.LightController
import io.github.tanzir71.harkara.tools.SosRepository
import io.github.tanzir71.harkara.tools.ToneSiren
import io.github.tanzir71.harkara.core.links.HarkaraLink
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.sms.SmsEligibility
import io.github.tanzir71.harkara.links.LinkInbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.sms.AndroidSmsReceivers
import io.github.tanzir71.harkara.sms.AndroidSmsSender
import io.github.tanzir71.harkara.sms.GatewayStore
import io.github.tanzir71.harkara.sms.GreyAreaSms
import io.github.tanzir71.harkara.sms.PrefsGatewayCounter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import io.github.tanzir71.harkara.map.MapPackManager
import io.github.tanzir71.harkara.places.AndroidLocationSource
import io.github.tanzir71.harkara.places.CheckInRepository
import io.github.tanzir71.harkara.places.GiftInbox
import io.github.tanzir71.harkara.places.HeadingSource
import io.github.tanzir71.harkara.places.LiveShareRepository
import io.github.tanzir71.harkara.places.LocationSource
import io.github.tanzir71.harkara.places.PinRepository
import io.github.tanzir71.harkara.places.SensorHeadingSource
import io.github.tanzir71.harkara.places.ServiceLiveShareHost
import io.github.tanzir71.harkara.places.WorkManagerOverdueScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Application root that owns manually wired dependencies. */
open class HarkaraApplication : Application() {
    @Volatile private var currentContainer: AppContainer? = null

    /** The old identity and in-memory carrier caches never cross a completed panic-wipe boundary. */
    open val container: AppContainer
        get() = synchronized(this) { currentContainer ?: createContainer().also { currentContainer = it } }

    protected open fun createContainer(): AppContainer = AppContainer(this)

    @Synchronized
    fun resetContainer(): AppContainer {
        check(container.isWiped) { "Panic wipe must finish before replacing the container" }
        return createContainer().also { currentContainer = it; it.greyAreaSms.sync() }
    }

    override fun onCreate() {
        super.onCreate()
        container.crashReporter.install()
        if (container.settings.state.value.onboarded) RelaySyncWorker.schedule(this)
        // SMS receivers are disabled components unless a grey-area switch is on (M10).
        container.greyAreaSms.sync()
    }
}

/** Manual dependency container. No Firebase, no Play Services. */
class AppContainer(
    private val context: Context,
    private val inMemoryDatabase: Boolean = false,
    private val databaseFactory: (() -> HarkaraDatabase)? = null,
) {
    private val wipeLock = Mutex()
    private val wiping = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    @Volatile var isWiped: Boolean = false
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val databaseInstance = lazy { databaseFactory?.invoke() ?: if (inMemoryDatabase) HarkaraDatabase.inMemory(context) else HarkaraDatabase.create(context) }
    val database: HarkaraDatabase
        get() = synchronized(databaseInstance) {
            check(!retired.get()) { "This container has been retired" }
            databaseInstance.value
        }
    val settings: AppSettings = AppSettings(context)
    val crashReporter = CrashReporter(context, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    val updateChecker = UpdateChecker(settings, BuildConfig.VERSION_CODE)
    val areas: DhakaAreas by lazy { DhakaAreas.load(context) }
    private val identityInstance = lazy { IdentityManager(FileSeedStore(seedFile(context))) }
    val identity: IdentityManager
        get() = synchronized(identityInstance) {
            check(!retired.get()) { "This container has been retired" }
            identityInstance.value
        }

    private val transportClientInstance = lazy { RelayPool.defaultClient() }
    private val relayPoolInstance = lazy { RelayPool(scope, client = transportClientInstance.value) }
    val relayPool: RelayPool by relayPoolInstance
    val eventStore: RoomEventStore by lazy { RoomEventStore(database) }
    val outbox: Outbox by lazy { Outbox(database, relayPool, routes = listOf(mesh.route, node.route, node.envelopeRoute)).also { it.start(scope) } }
    val wordFilters: WordFilters by lazy {
        WordFilters.parse(context.assets.open("word-filters/filters.json").bufferedReader().use { it.readText() })
    }
    val connectivity: ConnectivityMonitor by lazy { LiveConnectivityMonitor(relayPool, outbox, scope, { relays().size }, lanRoom.peerCount, lanRoom.nodePeers, mesh.status) }
    val feedRepository: FeedRepository by lazy {
        FeedRepository(
            database, eventStore, outbox, relayPool, wordFilters,
            areaKey = { g5 -> identity.keyRing().area(g5, IsoWeek.current()) },
            powBits = { powBits },
        ).also { it.startIngest(scope) }
    }

    /** "Everyone on this Wi-Fi": room server + NSD + a pool to the other phones; foreground only. */
    private val lanRoomInstance = lazy {
        LanRoom(scope, NsdLanDiscovery(context), pool = RelayPool(scope, client = transportClientInstance.value), accept = ::validCarriedEvent)
    }
    val lanRoom: LanRoom by lanRoomInstance
    private val meshInstance = lazy {
        io.github.tanzir71.harkara.mesh.MeshController(scope, database, eventStore, relayPool,
            presenceId = { identity.keyRing().meshPresenceId(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"))) },
            radios = { listOf(io.github.tanzir71.harkara.core.mesh.BleRadio(io.github.tanzir71.harkara.mesh.AndroidGattLayer(context), maxLinks = { if (settings.state.value.meshMode == io.github.tanzir71.harkara.settings.MeshMode.EVENT) 8 else 4 }), io.github.tanzir71.harkara.mesh.AwareRadio(context)) },
            areaPrefix = { areaCenter(settings.state.value).let { io.github.tanzir71.harkara.core.geo.GeoHash.encode(it.lat, it.lng, 4) } },
            ingest = ::ingestNearby,
            isNode = { node.node.value },
            accept = ::validCarriedEvent,
        )
    }
    val mesh: io.github.tanzir71.harkara.mesh.MeshController by meshInstance
    private val nodeInstance = lazy {
        io.github.tanzir71.harkara.lan.NodeController(scope, lanRoom, database, eventStore, relayPool, site = { isNode ->
            io.github.tanzir71.harkara.core.lan.NodeSite(
                app = { installedApp },
                mapPacks = { File(context.filesDir, "maps").listFiles()?.filter { it.isFile && it.extension == "pmtiles" }.orEmpty() },
                handbook = { name -> runCatching { context.assets.open("handbook/$name").use { it.readBytes() } }.getOrNull() },
                node = isNode,
            )
        }, ingest = ::ingestNearby)
    }
    val node: io.github.tanzir71.harkara.lan.NodeController by nodeInstance
    private val installedApp by lazy {
        val file = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        io.github.tanzir71.harkara.core.lan.NodeSite.AppFile(file, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Preserve each feature's validation and decryption on every off-grid carrier. */
    private fun validCarriedEvent(event: io.github.tanzir71.harkara.core.nostr.Event): Boolean =
        event.kind != io.github.tanzir71.harkara.core.nostr.Kinds.FEED_POST ||
            (io.github.tanzir71.harkara.core.feed.FeedPost.from(event) != null &&
                io.github.tanzir71.harkara.core.nostr.Nip13.difficulty(event.id) >= PostComposer.MIN_POW_BITS)

    private suspend fun ingestNearby(event: io.github.tanzir71.harkara.core.nostr.Event, carrier: io.github.tanzir71.harkara.core.Carrier): Boolean {
        val kinds = io.github.tanzir71.harkara.core.nostr.Kinds
        return when (event.kind) {
            kinds.FEED_POST, kinds.REPORT -> feedRepository.ingest(event, carrier)
            kinds.REACTION -> {
                val accepted = if (event.tagValue("a")?.startsWith("${kinds.PIN}:") == true) pinRepository.ingest(event) else feedRepository.ingest(event, carrier)
                if (accepted) eventStore.store(event, carrier, own = false)
                accepted
            }
            kinds.PIN, kinds.APP_DATA -> pinRepository.ingest(event).also { if (it) eventStore.store(event, carrier, own = false) }
            kinds.TASK_OFFER, kinds.TASK_STEP -> taskRepository.ingest(event, carrier)
            kinds.GIFT_WRAP -> {
                chatRepository.ingest(event, carrier)
                khataRepository.ingestWrap(event)
                startPlaces()
                giftInbox.offer(event)
                eventStore.store(event, carrier, own = false)
                true
            }
            kinds.AREA_CHAT -> { chatRepository.ingest(event, carrier); true }
            kinds.SMS_DM -> chatRepository.ingestSms(event, carrier)
            kinds.EPHEMERAL_GIFT_WRAP -> { startPlaces(); giftInbox.offer(event) != null }
            else -> eventStore.store(event, carrier, own = false)
        }
    }
    val chatRepository: ChatRepository by lazy {
        ChatRepository(
            database, outbox, relayPool, lanRoom,
            profile = { identity.profile() },
            areaKey = { g5 -> identity.keyRing().area(g5, IsoWeek.current()) },
            prefs = SettingsChatPrefs(settings),
        ).also { it.start(scope) }
    }
    private val voiceRecorderInstance = lazy { AndroidVoiceRecorder(context) }
    val voiceRecorder: VoiceRecorder by voiceRecorderInstance
    private val voicePlayerInstance = lazy { AndroidVoicePlayer(context) }
    val voicePlayer: VoicePlayer by voicePlayerInstance

    fun startLan() {
        check(!wiping.get()) { "Panic wipe is in progress" }
        chatRepository
        node.startSync()
        lanRoom.start()
    }

    fun stopLan() { if (!node.node.value && !node.sharing.value) lanRoom.stop() }

    val taskTypes: TaskTypes by lazy { TaskTypes.parse(context.assets.open("task-types.json").bufferedReader().use { it.readText() }) }
    val taskRepository: TaskRepository by lazy {
        TaskRepository(database, eventStore, outbox, relayPool, profile = { identity.profile() }, displayName = { settings.state.value.displayName })
            .also { it.startIngest(scope) }
    }

    val khataRepository: KhataRepository by lazy {
        KhataRepository(database, outbox, pools = { listOf(relayPool) }, profile = { identity.profile() }, displayName = { settings.state.value.displayName }, lan = lanRoom)
            .also { it.startIngest(scope, relayPool) }
    }

    val sosRepository: SosRepository by lazy {
        SosRepository(database, eventStore, outbox, relayPool, profile = { identity.profile() }).also { it.startIngest(scope) }
    }
    val handbook: Handbook by lazy { Handbook.fromAssets(context) }
    private val lightsInstance = lazy { LightController(CameraTorch(context), ToneSiren(), scope) }
    val lights: LightController by lightsInstance
    /** Deep links, shared text and pasted QR/SMS (M10): set by MainActivity, consumed by the UI. */
    val pendingLinks = MutableStateFlow<HarkaraLink?>(null)

    val linkInbox: LinkInbox by lazy {
        LinkInbox(database, eventStore, outbox, taskRepository, khataRepository, me = { identity.profilePubkey.value },
            ingestSms = { event, via -> chatRepository.ingestSms(event, via) },
            ingestContent = ::ingestNearby,
            ingestGift = { event, via -> startPlaces(); chatRepository.ingest(event, via); giftInbox.offer(event) != null },
        )
    }

    val gatewayStore: GatewayStore by lazy { GatewayStore(database) }

    /** The grey-area SMS switches (both off by default; inert while off). */
    val greyAreaSms: GreyAreaSms by lazy {
        GreyAreaSms(
            isOn = { settings.state.value.isOn(it) },
            sender = AndroidSmsSender(context),
            receivers = AndroidSmsReceivers(context),
            counter = PrefsGatewayCounter(settings),
            config = { gatewayStore.get() },
            resolver = { linkInbox.resolver() },
            deliver = { event, via -> linkInbox.ingest(event, via) },
            publish = { event -> outbox.enqueue(event, if (event.kind in SOS_KINDS) Priority.SOS else Priority.TASK) },
        )
    }

    private val gatewayRelayStarted = AtomicBoolean(false)

    /** While the gateway switch is on and relays run: events for members are texted to them. */
    suspend fun subscribeGateway() {
        check(!wiping.get()) { "Panic wipe is in progress" }
        if (!settings.state.value.isOn(Module.SMS_GATEWAY)) return
        if (gatewayRelayStarted.compareAndSet(false, true)) {
            scope.launch { relayPool.incoming.filter { it.subscriptionId == SUB_GATEWAY }.collect { greyAreaSms.forwardToMember(it.event) } }
        }
        val keys = gatewayStore.get().members.mapNotNull { it.pubkey }
        if (keys.isEmpty()) return
        relayPool.subscribe(SUB_GATEWAY, listOf(Filter(kinds = SmsEligibility.KINDS.toList(), tags = mapOf("p" to keys), since = System.currentTimeMillis() / 1000)))
    }

    // M9: map, pins and location. Sensors and GPS run only while a screen or a user-started share collects them.
    val giftInbox: GiftInbox by lazy { GiftInbox({ identity.profile() }) }
    val locationSource: LocationSource by lazy { AndroidLocationSource(context) }
    val headingSource: HeadingSource by lazy { SensorHeadingSource(context) }
    val mapPackManager: MapPackManager by lazy { MapPackManager(File(context.filesDir, "maps")) }
    val pinRepository: PinRepository by lazy {
        PinRepository(database, outbox, relayPool, { identity.profile() }, { g5 -> identity.keyRing().area(g5, IsoWeek.current()) })
            .also { it.startIngest(scope, giftInbox) }
    }
    val checkInRepository: CheckInRepository by lazy {
        CheckInRepository(database, outbox, { identity.profile() }, WorkManagerOverdueScheduler(context), displayName = { settings.state.value.displayName })
            .also {
                it.startIngest(giftInbox)
                it.onAlert = { alert -> io.github.tanzir71.harkara.places.CheckInAlerts.notify(context, alert) }
            }
    }
    private val liveShareInstance = lazy {
        LiveShareRepository(outbox, relayPool, { identity.profile() }, { identity.keyRing() }, locationSource, scope, { settings.state.value.displayName }, host = ServiceLiveShareHost(context),
            sendLocal = { event -> lanRoom.publish(event); if (mesh.running) mesh.route.carry(event) })
            .also { it.startIngest(giftInbox) }
    }
    val liveShareRepository: LiveShareRepository by liveShareInstance
    private val placesStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Registers every place feature with the inbox, then starts reading wraps (once). */
    fun startPlaces() {
        check(!wiping.get()) { "Panic wipe is in progress" }
        pinRepository
        checkInRepository
        liveShareRepository
        if (placesStarted.compareAndSet(false, true)) giftInbox.start(scope, relayPool)
    }

    /** NIP-13 difficulty tuned once to this phone's speed (~2 s of mining). */
    private val powBits: Int by lazy { PostComposer.tunePowBits(measureHashRate()) }

    /** True while an activity is visible; relays only run in the foreground or during a WorkManager sync. */
    @Volatile var foreground: Boolean = false

    fun relays(): List<String> = settings.state.value.relays ?: DefaultRelays.load(context)

    fun startRelays() {
        check(!wiping.get()) { "Panic wipe is in progress" }
        relayPool.setRelays(relays())
    }

    fun stopRelays() = relayPool.stop()

    /** Centre of the user's area: the saved fix, else the named area, else central Dhaka. */
    fun areaCenter(state: SettingsState): LatLng {
        if (state.areaLat != null && state.areaLng != null) return LatLng(state.areaLat, state.areaLng)
        val named = state.areaName?.let { name -> areas.all.firstOrNull { it.name == name } }
        return named?.let { LatLng(it.lat, it.lng) } ?: LatLng(23.7808, 90.4142)
    }

    /** Terminal operation: no new onboarding or identity may use this container afterward. */
    suspend fun panicWipe() = withContext(Dispatchers.IO + NonCancellable) {
        wipeLock.withLock {
            if (isWiped) return@withLock
            wiping.set(true)
            foreground = false
            settings.setModule(Module.MESH, false)
            settings.setModule(Module.NODE, false)
            settings.setModule(Module.SMS_DIRECT, false)
            settings.setModule(Module.SMS_GATEWAY, false)
            greyAreaSms.sync()
            // Wait for WorkManager's cancellation transaction; never delete its own open database.
            val work = try { androidx.work.WorkManager.getInstance(context) } catch (_: IllegalStateException) { null }
            work?.cancelAllWork()?.result?.get()
            context.stopService(android.content.Intent(context, io.github.tanzir71.harkara.mesh.MeshForegroundService::class.java))
            context.stopService(android.content.Intent(context, io.github.tanzir71.harkara.places.LiveShareService::class.java))
            if (meshInstance.isInitialized()) meshInstance.value.stop()
            if (nodeInstance.isInitialized()) {
                nodeInstance.value.stopSharing()
                nodeInstance.value.stopNode(keepRoom = false)
            }
            if (lanRoomInstance.isInitialized()) lanRoomInstance.value.stop()
            if (relayPoolInstance.isInitialized()) relayPoolInstance.value.stop()
            // Cancel blocking NIP-11 calls too, or cancelAndJoin could wait on an unbounded socket read.
            if (transportClientInstance.isInitialized()) transportClientInstance.value.dispatcher.cancelAll()
            // Includes live GPS callbackFlow cleanup and all repository/relay consumers.
            scope.coroutineContext[Job]!!.cancelAndJoin()
            if (transportClientInstance.isInitialized()) {
                transportClientInstance.value.connectionPool.evictAll()
                transportClientInstance.value.dispatcher.executorService.shutdownNow()
            }
            if (liveShareInstance.isInitialized()) liveShareInstance.value.stop()
            if (voiceRecorderInstance.isInitialized()) voiceRecorderInstance.value.cancel()
            if (voicePlayerInstance.isInitialized()) voicePlayerInstance.value.stop()
            if (lightsInstance.isInitialized()) lightsInstance.value.stop()
            pendingLinks.value = null
            // Join any in-flight lazy initialization before capturing the handle to close.
            val oldDatabase = synchronized(databaseInstance) {
                synchronized(identityInstance) {
                    retired.set(true)
                    databaseInstance.takeIf { it.isInitialized() }?.value
                }
            }
            PanicWipeController.wipeEverything(context, oldDatabase, settings) {
                if (identityInstance.isInitialized()) identityInstance.value.wipe()
                else FileSeedStore(seedFile(context)).clear()
            }
            isWiped = true
        }
    }

    private fun measureHashRate(): Double {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = ByteArray(220) { it.toByte() }
        val n = 20_000
        val start = System.nanoTime()
        repeat(n) { input[0] = it.toByte(); digest.digest(input) }
        val seconds = (System.nanoTime() - start) / 1e9
        return n / seconds.coerceAtLeast(1e-6)
    }

    companion object {
        /** App-private, excluded from backups, and inside a directory the panic wipe empties. */
        fun seedFile(context: Context): File = File(context.noBackupFilesDir, "identity/root-seed")

        const val SUB_GATEWAY = "harkara-sms-gateway"
        private val SOS_KINDS = setOf(Kinds.SOS, Kinds.SOS_UPDATE, Kinds.CHECK_IN)
    }
}

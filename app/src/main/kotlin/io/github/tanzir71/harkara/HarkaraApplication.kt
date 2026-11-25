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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Application root that owns manually wired dependencies. */
class HarkaraApplication : Application() {
    /** Lazily created app container. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.crashReporter.install()
        if (container.settings.state.value.onboarded) RelaySyncWorker.schedule(this)
    }
}

/** Manual dependency container. No Firebase, no Play Services. */
class AppContainer(private val context: Context, private val inMemoryDatabase: Boolean = false) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: HarkaraDatabase by lazy { if (inMemoryDatabase) HarkaraDatabase.inMemory(context) else HarkaraDatabase.create(context) }
    val settings: AppSettings = AppSettings(context)
    val crashReporter = CrashReporter(context, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    val updateChecker = UpdateChecker(settings, BuildConfig.VERSION_CODE)
    val areas: DhakaAreas by lazy { DhakaAreas.load(context) }
    val identity: IdentityManager by lazy { IdentityManager(FileSeedStore(seedFile(context))) }

    val relayPool: RelayPool by lazy { RelayPool(scope) }
    val RoomEventStore: RoomEventStore by lazy { RoomEventStore(database) }
    val outbox: Outbox by lazy { Outbox(database, relayPool).also { it.start(scope) } }
    val wordFilters: WordFilters by lazy {
        WordFilters.parse(context.assets.open("word-filters/filters.json").bufferedReader().use { it.readText() })
    }
    val connectivity: ConnectivityMonitor by lazy { LiveConnectivityMonitor(relayPool, outbox, scope, { relays().size }, lanRoom.peerCount) }
    val feedRepository: FeedRepository by lazy {
        FeedRepository(
            database, RoomEventStore, outbox, relayPool, wordFilters,
            areaKey = { g5 -> identity.keyRing().area(g5, IsoWeek.current()) },
            powBits = { powBits },
        ).also { it.startIngest(scope) }
    }

    /** "Everyone on this Wi-Fi": room server + NSD + a pool to the other phones; foreground only. */
    val lanRoom: LanRoom by lazy { LanRoom(scope, NsdLanDiscovery(context)) }
    val chatRepository: ChatRepository by lazy {
        ChatRepository(
            database, outbox, relayPool, lanRoom,
            profile = { identity.profile() },
            areaKey = { g5 -> identity.keyRing().area(g5, IsoWeek.current()) },
            prefs = SettingsChatPrefs(settings),
        ).also { it.start(scope) }
    }
    val voiceRecorder: VoiceRecorder by lazy { AndroidVoiceRecorder(context) }
    val voicePlayer: VoicePlayer by lazy { AndroidVoicePlayer(context) }

    fun startLan() {
        chatRepository
        lanRoom.start()
    }

    fun stopLan() = lanRoom.stop()

    /** NIP-13 difficulty tuned once to this phone's speed (~2 s of mining). */
    private val powBits: Int by lazy { PostComposer.tunePowBits(measureHashRate()) }

    /** True while an activity is visible; relays only run in the foreground or during a WorkManager sync. */
    @Volatile var foreground: Boolean = false

    fun relays(): List<String> = settings.state.value.relays ?: DefaultRelays.load(context)

    fun startRelays() = relayPool.setRelays(relays())

    fun stopRelays() = relayPool.stop()

    /** Centre of the user's area: the saved fix, else the named area, else central Dhaka. */
    fun areaCenter(state: SettingsState): LatLng {
        if (state.areaLat != null && state.areaLng != null) return LatLng(state.areaLat, state.areaLng)
        val named = state.areaName?.let { name -> areas.all.firstOrNull { it.name == name } }
        return named?.let { LatLng(it.lat, it.lng) } ?: LatLng(23.7808, 90.4142)
    }

    suspend fun panicWipe() {
        stopRelays()
        stopLan()
        withContext(Dispatchers.IO) { identity.wipe() }
        PanicWipeController.wipeEverything(context, database, settings)
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
    }
}

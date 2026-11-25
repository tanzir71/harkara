package io.github.tanzir71.harkara.relay

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tanzir71.harkara.HarkaraApplication
import io.github.tanzir71.harkara.connectivity.ConnectivityMonitor
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.core.store.StorageBudget
import io.github.tanzir71.harkara.core.store.StoredItem
import io.github.tanzir71.harkara.data.HarkaraDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

@Serializable
private data class DefaultRelaysFile(val relays: List<String>)

/** Bundled `data/default-relays.json` (rewritten weekly by relay-check.yml). */
object DefaultRelays {
    val FALLBACK = listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://relay.nostr.net")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<String> = runCatching { json.decodeFromString(DefaultRelaysFile.serializer(), text).relays }.getOrDefault(FALLBACK)

    fun load(context: Context): List<String> =
        runCatching { parse(context.assets.open("default-relays.json").bufferedReader().use { it.readText() }) }.getOrDefault(FALLBACK)
}

/** Connectivity from the live relay pool and outbox: Online when at least one relay is connected. */
class LiveConnectivityMonitor(pool: RelayPool, outbox: Outbox, scope: CoroutineScope, configured: () -> Int) : ConnectivityMonitor {
    override val state: StateFlow<ConnectivityState> = combine(pool.status, outbox.waiting) { status, waiting ->
        val connected = status.values.count { it == RelayStatus.CONNECTED }
        val available = buildSet {
            if (connected > 0) add(Carrier.ONLINE)
            add(Carrier.QR)
            add(Carrier.SAVED)
        }
        ConnectivityState(available = available, relaysReachable = connected, relaysConfigured = configured(), waiting = waiting)
    }.stateIn(scope, SharingStarted.Eagerly, ConnectivityState.Saved)
}

/** Deletes expired posts and keeps other people's events inside the 20 MB budget. */
object Maintenance {
    suspend fun run(db: HarkaraDatabase, nowMillis: Long, budget: StorageBudget = StorageBudget()): Int {
        val expired = db.eventDao().purgeExpired(nowMillis / 1000)
        val rows = db.eventDao().storedRows().map { StoredItem(it.id, it.bytes.toLong(), it.receivedAt, it.own) }
        val evict = budget.evictions(rows)
        evict.chunked(500).forEach { db.eventDao().deleteOthers(it) }
        return expired + evict.size
    }
}

/**
 * Background check every 30 minutes, only with network and battery not low (§4 battery rule): connect,
 * send what's waiting, fetch the area feed, tidy storage, disconnect. No Firebase, no Play Services.
 */
class RelaySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? HarkaraApplication ?: return Result.failure()
        val container = app.container
        if (!container.settings.state.value.onboarded) return Result.success()
        // Check-ins and overdue alerts addressed to us arrive during the background check too (M9).
        runCatching {
            container.startPlaces()
            container.giftInbox.subscribe(container.relayPool)
        }
        container.startRelays()
        withTimeoutOrNull(45_000) {
            container.relayPool.status.first { s -> s.values.any { it == RelayStatus.CONNECTED } }
            container.outbox.flush()
            delay(15_000)
        }
        Maintenance.run(container.database, System.currentTimeMillis())
        if (!container.foreground) container.stopRelays()
        return Result.success()
    }

    companion object {
        const val NAME = "harkara-relay-sync"

        fun schedule(context: Context, minutes: Long = 30) {
            val request = PeriodicWorkRequestBuilder<RelaySyncWorker>(minutes.coerceIn(15, 60), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

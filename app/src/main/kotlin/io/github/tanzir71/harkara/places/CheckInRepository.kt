package io.github.tanzir71.harkara.places

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.tanzir71.harkara.HarkaraApplication
import io.github.tanzir71.harkara.core.location.CheckIn
import io.github.tanzir71.harkara.core.location.CheckInStatus
import io.github.tanzir71.harkara.core.location.CheckIns
import io.github.tanzir71.harkara.core.location.TripPlan
import io.github.tanzir71.harkara.core.location.TripRules
import io.github.tanzir71.harkara.core.location.TripStatus
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.data.CheckInEntity
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.TripEntity
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A check-in row for the UI. */
data class CheckInView(val checkIn: CheckIn, val own: Boolean)

/** Wakes the phone once at a trip's expected arrival; nothing runs while no trip is active. */
interface OverdueScheduler {
    fun schedule(tripId: String, atMillis: Long)
    fun cancel(tripId: String)
}

/**
 * Check-ins ("I got here safe"), trip plans and overdue alerts, all gift-wrapped to the chosen people
 * at the top of the outbox. A trip schedules one WorkManager job at its expected arrival; if no safe
 * check-in came by then, the phone sends the overdue alert itself.
 */
class CheckInRepository(
    private val db: HarkaraDatabase,
    private val outbox: Outbox,
    private val profile: () -> KeyPair,
    private val scheduler: OverdueScheduler,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val now get() = nowMillis() / 1000
    private val json = Json { ignoreUnknownKeys = true }

    fun startIngest(inbox: GiftInbox) {
        inbox.register(setOf(Kinds.CHECK_IN)) { rumor, _ -> receive(rumor) }
    }

    /** Check-ins from the last [days] days, newest first. */
    fun recent(days: Int = 7): Flow<List<CheckInView>> = db.checkInDao().observeSince(now - days * 86_400L).map { rows ->
        rows.mapNotNull { it.toCheckIn()?.let { c -> CheckInView(c, it.own) } }
    }

    fun activeTrip(): Flow<TripPlan?> = db.tripDao().observeActive().map { it?.let { e -> json.decodeFromString(TripPlan.serializer(), e.json) } }

    suspend fun receive(rumor: Rumor): Boolean {
        val c = CheckIns.parse(rumor) ?: return false
        val me = profile().pubkeyHex
        if (c.author != me && me !in c.recipients) return false
        return save(c, own = c.author == me)
    }

    private suspend fun save(c: CheckIn, own: Boolean): Boolean =
        db.checkInDao().insert(
            CheckInEntity(c.id, c.author, c.status.wire, c.place, c.geohash, c.tripId, c.destination, c.due, c.note, c.createdAt, c.recipients.joinToString("\n"), own, nowMillis()),
        ) != -1L

    private suspend fun send(rumor: Rumor) {
        CheckIns.parse(rumor)?.let { save(it, own = true) }
        CheckIns.wraps(profile(), rumor).forEach { outbox.enqueue(it, Priority.SOS) }
    }

    /** "I got here safe" to [recipients]; closes the active trip, if any. */
    suspend fun checkIn(place: String, recipients: List<String>, geohash5: String? = null, note: String = ""): CheckIn {
        val trip = currentTrip()
        val people = (recipients + (trip?.watchers ?: emptyList())).distinct()
        val rumor = CheckIns.rumor(profile(), CheckInStatus.SAFE, place, people, now, geohash5, trip?.id, trip?.destination, null, note)
        send(rumor)
        if (trip != null) {
            putTrip(trip.copy(status = TripStatus.ARRIVED))
            scheduler.cancel(trip.id)
        }
        return CheckIns.parse(rumor)!!
    }

    /** Starts a trip: tells the watchers where and by when, and wakes up at [due] to check. */
    suspend fun startTrip(destination: String, due: Long, watchers: List<String>, from: String): TripPlan {
        currentTrip()?.let { cancelTrip() }
        val trip = TripPlan(UUID.randomUUID().toString(), destination.trim(), due, watchers.distinct(), now)
        putTrip(trip)
        send(TripRules.startRumor(profile(), trip, from))
        scheduler.schedule(trip.id, due * 1000)
        return trip
    }

    suspend fun cancelTrip() {
        val trip = currentTrip() ?: return
        putTrip(trip.copy(status = TripStatus.CANCELLED))
        scheduler.cancel(trip.id)
        send(CheckIns.rumor(profile(), CheckInStatus.CANCELLED, trip.destination, trip.watchers, now, tripId = trip.id))
    }

    /**
     * Run at the expected arrival (by [OverdueWorker]): sends the overdue alert when no safe check-in came
     * since the trip started. Returns true when an alert went out. Too early runs reschedule themselves.
     */
    suspend fun checkOverdue(tripId: String? = null, lastPlace: String? = null): Boolean {
        val trip = currentTrip()?.takeIf { tripId == null || it.id == tripId } ?: return false
        if (now < trip.due) {
            scheduler.schedule(trip.id, trip.due * 1000)
            return false
        }
        val own = db.checkInDao().ownSince(trip.startedAt).mapNotNull { it.toCheckIn() }.filter { it.author == profile().pubkeyHex }
        if (!TripRules.isOverdue(trip, own, now)) {
            putTrip(trip.copy(status = TripStatus.ARRIVED))
            return false
        }
        putTrip(trip.copy(status = TripStatus.OVERDUE))
        send(TripRules.overdueRumor(profile(), trip, lastPlace ?: trip.destination, now))
        return true
    }

    private suspend fun currentTrip(): TripPlan? = db.tripDao().active()?.let { json.decodeFromString(TripPlan.serializer(), it.json) }

    private suspend fun putTrip(trip: TripPlan) =
        db.tripDao().upsert(TripEntity(trip.id, json.encodeToString(TripPlan.serializer(), trip), trip.status.name, trip.due, nowMillis()))

    /** The active trip as of now (for tests and the worker). */
    suspend fun trip(): TripPlan? = currentTrip()

    suspend fun tripById(id: String): TripPlan? = db.tripDao().get(id)?.let { json.decodeFromString(TripPlan.serializer(), it.json) }
}

private fun CheckInEntity.toCheckIn(): CheckIn? {
    val s = CheckInStatus.of(status) ?: return null
    return CheckIn(id, author, s, place, geohash, tripId, destination, due, note, createdAt, recipients.split('\n').filter { it.isNotBlank() })
}

/** One-off WorkManager job per active trip (unique per app: one trip at a time). */
class WorkManagerOverdueScheduler(private val context: Context, private val nowMillis: () -> Long = System::currentTimeMillis) : OverdueScheduler {
    override fun schedule(tripId: String, atMillis: Long) {
        val request = OneTimeWorkRequestBuilder<OverdueWorker>()
            .setInitialDelay((atMillis - nowMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(OverdueWorker.KEY_TRIP to tripId))
            .addTag(OverdueWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(OverdueWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(tripId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(OverdueWorker.NAME)
    }
}

/**
 * Runs once at a trip's expected arrival. If the traveller hasn't checked in, it queues the overdue alert
 * and tries to get it out over the relays right away; otherwise the outbox sends it on the next rung.
 */
class OverdueWorker(
    context: Context,
    params: WorkerParameters,
    private val checkIns: () -> CheckInRepository?,
    private val flush: suspend () -> Unit,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(
        context, params,
        { (context.applicationContext as? HarkaraApplication)?.container?.checkInRepository },
        { (context.applicationContext as? HarkaraApplication)?.container?.let { c -> flushRelays(c) } },
    )

    override suspend fun doWork(): Result {
        val repo = checkIns() ?: return Result.success()
        val sent = repo.checkOverdue(inputData.getString(KEY_TRIP))
        if (sent) flush()
        return Result.success()
    }

    companion object {
        const val NAME = "harkara-trip-overdue"
        const val TAG = "harkara-trip"
        const val KEY_TRIP = "trip"

        private suspend fun flushRelays(c: io.github.tanzir71.harkara.AppContainer) {
            if (!c.settings.state.value.onboarded) return
            c.startRelays()
            withTimeoutOrNull(30_000) {
                c.relayPool.status.first { s -> s.values.any { it == RelayStatus.CONNECTED } }
                c.outbox.flush()
                delay(5_000)
            }
            if (!c.foreground) c.stopRelays()
        }
    }
}

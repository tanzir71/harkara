package io.github.tanzir71.harkara.tasks

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Nip44
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.tasks.DisputeReason
import io.github.tanzir71.harkara.core.tasks.Handoff
import io.github.tanzir71.harkara.core.tasks.StepType
import io.github.tanzir71.harkara.core.tasks.TaskOffer
import io.github.tanzir71.harkara.core.tasks.TaskOffers
import io.github.tanzir71.harkara.core.tasks.TaskState
import io.github.tanzir71.harkara.core.tasks.TaskStateMachine
import io.github.tanzir71.harkara.core.tasks.TaskStatus
import io.github.tanzir71.harkara.core.tasks.TaskSteps
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.LocalSecretEntity
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RoomEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** How a payment was made; TrxIDs are typed as text, never fetched from any wallet. */
enum class PayMethod(val wire: String) { CASH("cash"), BKASH("bkash"), NAGAD("nagad") }

@Serializable
data class PaymentDetails(val method: String, val amount: Long, val trxId: String = "")

/** What the handoff card shows to the requester. */
data class HandoffDisplay(val token: Event, val code: String) {
    val qrPayload: String get() = HANDOFF_QR_PREFIX + token.toJson()
}

const val HANDOFF_QR_PREFIX = "harkara:handoff:"

/**
 * Tasks: NIP-99 offers and signed steps, carried like every other event. The state of each task is
 * recomputed from its events by [TaskStateMachine], so it works the same offline.
 */
class TaskRepository(
    private val db: HarkaraDatabase,
    private val store: RoomEventStore,
    private val outbox: Outbox,
    private val pool: RelayPool,
    private val profile: () -> KeyPair,
    private val displayName: () -> String? = { null },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val now get() = nowMillis() / 1000

    @OptIn(ExperimentalCoroutinesApi::class)
    fun tasks(center: LatLng): Flow<List<TaskState>> {
        val cells = GeoMath.withNeighbours(GeoHash.encode(center.lat, center.lng, 5))
        return db.eventDao().observeTaskOffers(cells).flatMapLatest { offers ->
            if (offers.isEmpty()) {
                flowOf(emptyList())
            } else {
                db.eventDao().observeSteps(offers.map { it.id }).map { steps ->
                    val byOffer = steps.groupBy { it.target }
                    offers.mapNotNull { row ->
                        val offer = Event.fromJson(row.json)
                        TaskStateMachine.reduce(offer, byOffer[row.id].orEmpty().map { Event.fromJson(it.json) }, now)
                    }
                }
            }
        }
    }

    fun subscribeArea(center: LatLng) {
        val cells = GeoMath.withNeighbours(GeoHash.encode(center.lat, center.lng, 5))
        val me = profile().pubkeyHex
        pool.subscribe(
            SUB_TASKS,
            listOf(
                Filter(kinds = listOf(Kinds.TASK_OFFER), tags = mapOf("g" to cells, "t" to listOf(TaskOffers.MARKER)), since = now - 3 * 86_400, limit = 200),
                Filter(kinds = listOf(Kinds.TASK_STEP, Kinds.TASK_OFFER), tags = mapOf("p" to listOf(me))),
                Filter(kinds = listOf(Kinds.TASK_OFFER), authors = listOf(me)),
            ),
        )
    }

    /** Follows the steps on the offers currently shown. */
    fun subscribeSteps(offerIds: List<String>) {
        if (offerIds.isNotEmpty()) pool.subscribe(SUB_STEPS, listOf(Filter(kinds = listOf(Kinds.TASK_STEP), tags = mapOf("e" to offerIds.take(200)))))
    }

    fun startIngest(scope: CoroutineScope) {
        scope.launch {
            pool.incoming.collect { ingest(it.event, Carrier.ONLINE) }
        }
    }

    suspend fun ingest(event: Event, carrier: Carrier): Boolean = when (event.kind) {
        Kinds.TASK_OFFER -> TaskOffers.parse(event) != null && store.store(event, carrier, own = false)
        Kinds.TASK_STEP -> TaskSteps.stepOf(event) != null && store.store(event, carrier, own = false)
        else -> false
    }

    private suspend fun publish(event: Event, priority: Int = Priority.TASK): Event {
        store.store(event, Carrier.SAVED, own = true)
        outbox.enqueue(event, priority)
        return event
    }

    suspend fun post(type: String, title: String, price: Long, fields: Map<String, String>, description: String, at: LatLng): Event =
        publish(
            TaskOffers.build(profile(), UUID.randomUUID().toString(), title, type, price, at.lat, at.lng, description, now, fields, name = displayName()),
        )

    suspend fun accept(offer: TaskOffer) = publish(TaskSteps.accept(profile(), offer, now, displayName()))

    /**
     * Countersigns [accept]: makes the single-use handoff token now, keeps it only on this phone, and
     * publishes the commitment to its 6-digit code.
     */
    suspend fun confirm(offer: TaskOffer, accept: Event): Event {
        val keys = profile()
        val token = Handoff.token(keys, offer, accept.pubkey, now)
        val code = Handoff.code(token)
        val commitment = withContext(Dispatchers.Default) { Handoff.commitment(code, offer.address, accept.pubkey) }
        db.localSecretDao().put(LocalSecretEntity(tokenKey(offer), token.toJson(), nowMillis()))
        return publish(TaskSteps.confirm(keys, offer, accept, commitment, now))
    }

    /** The requester's handoff card: QR (the signed token) and the 6 digits. */
    suspend fun handoffFor(offer: TaskOffer): HandoffDisplay? =
        db.localSecretDao().get(tokenKey(offer))?.let { json -> Event.fromJson(json).let { HandoffDisplay(it, Handoff.code(it)) } }

    suspend fun pickedUp(offer: TaskOffer) = publish(TaskSteps.pickedUp(profile(), offer, now))

    /**
     * Runner side, fully offline: checks a typed code against the requester's commitment (or a scanned
     * QR token against the requester's signature) and records Delivered. Returns false when it doesn't check.
     */
    suspend fun deliver(state: TaskState, entered: String, thumbnail: List<String>? = null): Boolean {
        val keys = profile()
        val me = keys.pubkeyHex
        if (state.runner != me) return false
        val trimmed = entered.trim()
        val step = if (trimmed.startsWith(HANDOFF_QR_PREFIX) || trimmed.startsWith("{")) {
            val token = runCatching { Event.fromJson(trimmed.removePrefix(HANDOFF_QR_PREFIX)) }.getOrNull() ?: return false
            if (!Handoff.verifyToken(token, state.offer, me)) return false
            TaskSteps.deliveredWithToken(keys, state.offer, token, now, thumbnail)
        } else {
            val code = trimmed.filter(Char::isDigit)
            val commitment = state.commitment ?: return false
            val ok = withContext(Dispatchers.Default) { Handoff.verifyCode(code, commitment, state.offer, me) }
            if (!ok) return false
            TaskSteps.deliveredWithCode(keys, state.offer, code, now, thumbnail)
        }
        publish(step)
        return true
    }

    suspend fun recordPayment(state: TaskState, method: PayMethod, trxId: String) {
        val keys = profile()
        val runner = state.runner ?: return
        val details = Json.encodeToString(PaymentDetails.serializer(), PaymentDetails(method.wire, state.offer.price, trxId.trim()))
        val encrypted = Nip44.encrypt(details, Nip44.conversationKey(keys.privateKey, runner.hexBytes()))
        publish(TaskSteps.paid(keys, state.offer, runner, encrypted, now))
    }

    /** Reads the payment details the requester encrypted to us (runner) or that we sent (requester). */
    fun paymentDetails(state: TaskState): PaymentDetails? {
        val paid = state.applied.lastOrNull { it.type == StepType.PAID }?.event ?: return null
        val keys = profile()
        val other = if (keys.pubkeyHex == state.requester) state.runner ?: return null else state.requester
        return runCatching {
            Json.decodeFromString(PaymentDetails.serializer(), Nip44.decrypt(paid.content, Nip44.conversationKey(keys.privateKey, other.hexBytes())))
        }.getOrNull()
    }

    suspend fun confirmPayment(state: TaskState) = publish(TaskSteps.paidAck(profile(), state.offer, now))

    suspend fun rate(state: TaskState, stars: Int) {
        val keys = profile()
        val other = if (keys.pubkeyHex == state.requester) state.runner ?: return else state.requester
        publish(TaskSteps.rate(keys, state.offer, other, stars, now))
    }

    suspend fun cancel(state: TaskState) = publish(TaskSteps.cancel(profile(), state.offer, now))

    suspend fun decline(state: TaskState) = publish(TaskSteps.decline(profile(), state.offer, now))

    suspend fun dispute(state: TaskState, reason: DisputeReason, note: String) {
        val keys = profile()
        val other = if (keys.pubkeyHex == state.requester) state.runner ?: return else state.requester
        publish(TaskSteps.dispute(keys, state.offer, other, reason, now, note))
    }

    /** Which button the detail screen offers this person right now. */
    fun nextAction(state: TaskState): TaskAction? {
        val me = profile().pubkeyHex
        val isRequester = me == state.requester
        val isRunner = me == state.runner
        return when (state.status) {
            TaskStatus.OPEN -> when {
                isRequester && state.pendingAccepts.isNotEmpty() -> TaskAction.CONFIRM_RUNNER
                !isRequester && state.pendingAccepts.none { it.pubkey == me } -> TaskAction.ACCEPT
                else -> null
            }
            TaskStatus.ACCEPTED -> if (isRunner) TaskAction.PICKED_UP else null
            TaskStatus.PICKED_UP -> if (isRunner) TaskAction.ENTER_CODE else null
            TaskStatus.DELIVERED -> when {
                isRequester && !state.requesterPaid -> TaskAction.RECORD_PAYMENT
                isRunner && state.requesterPaid -> TaskAction.CONFIRM_PAYMENT
                (isRequester || isRunner) && me !in state.ratings -> TaskAction.RATE
                else -> null
            }
            TaskStatus.PAID -> if ((isRequester || isRunner) && me !in state.ratings) TaskAction.RATE else null
            else -> null
        }
    }

    private fun tokenKey(offer: TaskOffer) = "handoff:" + offer.address

    companion object {
        const val SUB_TASKS = "harkara-tasks"
        const val SUB_STEPS = "harkara-task-steps"
    }
}

enum class TaskAction { ACCEPT, CONFIRM_RUNNER, PICKED_UP, ENTER_CODE, RECORD_PAYMENT, CONFIRM_PAYMENT, RATE }

private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

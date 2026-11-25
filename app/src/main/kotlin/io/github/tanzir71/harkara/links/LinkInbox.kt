package io.github.tanzir71.harkara.links

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.khata.Iou
import io.github.tanzir71.harkara.core.khata.IouBundle
import io.github.tanzir71.harkara.core.links.HarkaraLink
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.sms.GatewayCard
import io.github.tanzir71.harkara.core.wire.PrefixResolver
import io.github.tanzir71.harkara.core.wire.QrAssembler
import io.github.tanzir71.harkara.core.wire.SmsCodec
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.LocalSecretEntity
import io.github.tanzir71.harkara.khata.KhataRepository
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.tasks.HANDOFF_QR_PREFIX
import io.github.tanzir71.harkara.tasks.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Acts on what a link, QR code or SMS carried ([DeepLinkRouter]): stores verified events through the same
 * doors as every other carrier, lifts SOS/check-ins and task steps that arrived off-grid onto the relays
 * (re-encoded, never re-signed), and tells the UI where to go next.
 */
class LinkInbox(
    private val db: HarkaraDatabase,
    private val store: RoomEventStore,
    private val outbox: Outbox,
    private val tasks: TaskRepository,
    private val khata: KhataRepository,
    private val me: () -> String?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ingestSms: suspend (Event, Carrier) -> Boolean = { _, _ -> false },
    private val ingestContent: suspend (Event, Carrier) -> Boolean = { _, _ -> false },
    private val ingestGift: suspend (Event, Carrier) -> Boolean = { _, _ -> false },
) {
    private val assembler = QrAssembler(nowMillis)

    sealed interface Outcome {
        /** A verified event, stored where its kind belongs ([isNew] false when we already had it). */
        data class Received(val event: Event, val via: Carrier, val isNew: Boolean) : Outcome

        /** One code of a multi-QR set; keep scanning. */
        data class Progress(val have: Int, val total: Int) : Outcome

        /** An SMS whose keys this phone doesn't know, or whose signature fails. */
        data object Unverified : Outcome

        data class OpenKhataImport(val text: String) : Outcome

        /** A confirm link: [iouId] when it's an IOU on this phone, null when it isn't ours. */
        data class OpenIou(val iouId: String?) : Outcome

        /** A handoff QR: the task's address so the UI can open its code entry with the token prefilled. */
        data class OpenHandoff(val text: String, val offerAddress: String?) : Outcome

        data class FollowTask(val requester: String, val d: String) : Outcome

        data class OpenArea(val geohash: String) : Outcome

        data class SavedGateway(val card: GatewayCard) : Outcome
    }

    suspend fun handle(link: HarkaraLink, via: Carrier = Carrier.QR): Outcome = when (link) {
        is HarkaraLink.EventLink -> Outcome.Received(link.event, via, ingest(link.event, via))
        is HarkaraLink.QrPart -> when (val r = assembler.add(link.text)) {
            is QrAssembler.Result.Complete -> Outcome.Received(r.event, Carrier.QR, ingest(r.event, Carrier.QR))
            is QrAssembler.Result.Progress -> Outcome.Progress(r.have, r.total)
            QrAssembler.Result.Invalid -> Outcome.Unverified
        }
        is HarkaraLink.Sms -> {
            val event = withContext(Dispatchers.IO) { SmsCodec.decodeText(link.text, resolver()) }
            if (event == null) Outcome.Unverified else Outcome.Received(event, Carrier.SMS, ingest(event, Carrier.SMS))
        }
        is HarkaraLink.IouBundle -> Outcome.OpenKhataImport(link.text)
        is HarkaraLink.IouConfirm -> Outcome.OpenIou(link.link.terms.id.takeIf { khata.get(it) != null })
        is HarkaraLink.Handoff -> Outcome.OpenHandoff(
            link.text,
            runCatching { Event.fromJson(link.text.removePrefix(HANDOFF_QR_PREFIX)).tagValue("a") }.getOrNull(),
        )
        is HarkaraLink.Task -> {
            tasks.follow(link.requester, link.d)
            Outcome.FollowTask(link.requester, link.d)
        }
        is HarkaraLink.Area -> Outcome.OpenArea(link.geohash)
        is HarkaraLink.Gateway -> {
            db.localSecretDao().put(LocalSecretEntity(GATEWAY_CARD_KEY, link.card.toLink(), nowMillis()))
            Outcome.SavedGateway(link.card)
        }
    }

    /** Stores a verified event by kind. Returns true when it was new to this phone. */
    suspend fun ingest(event: Event, via: Carrier): Boolean {
        if (!event.validate()) return false
        val offGrid = via != Carrier.ONLINE
        return when (event.kind) {
            Kinds.SMS_DM -> ingestSms(event, via)
            Kinds.PIN, Kinds.APP_DATA, Kinds.FEED_POST, Kinds.REACTION, Kinds.REPORT -> ingestContent(event, via)
            Kinds.EPHEMERAL_GIFT_WRAP -> ingestGift(event, via)
            Kinds.GIFT_WRAP -> {
                val iou = khata.ingestWrap(event)
                val other = ingestGift(event, via)
                val stored = store.store(event, via, own = false)
                iou || other || stored
            }
            Kinds.TASK_OFFER -> tasks.ingest(event, via)
            Kinds.TASK_STEP -> tasks.ingest(event, via).also { if (it && offGrid) outbox.enqueue(event, Priority.TASK) }
            Kinds.IOU_SIGN, Kinds.SETTLE -> khata.ingestEvent(event)
            Kinds.SOS, Kinds.SOS_UPDATE, Kinds.CHECK_IN -> store.store(event, via, own = false).also { if (it && offGrid) outbox.enqueue(event, Priority.SOS) }
            else -> store.store(event, via, own = false)
        }
    }

    /** The volunteer gateway card scanned from a QR, if any (its number is the SMS recipient to suggest). */
    suspend fun savedGateway(): GatewayCard? = db.localSecretDao().get(GATEWAY_CARD_KEY)?.let(GatewayCard::parse)

    /**
     * Expands 8-byte SMS references to values this phone knows: our key, keys and ids of stored events, IOU
     * hashes and IOU parties. Blocking: call from a background dispatcher.
     */
    fun resolver(): PrefixResolver = PrefixResolver { prefix ->
        val p = Hex.encode(prefix)
        val dao = db.lookupDao()
        buildList {
            me()?.let { if (it.startsWith(p)) add(it) }
            addAll(dao.pubkeysWithPrefix(p))
            addAll(dao.contactKeysWithPrefix(p))
            addAll(dao.idsWithPrefix(p))
            dao.iouBundles().forEach { json ->
                val terms = runCatching { IouBundle.fromJson(json).terms }.getOrNull() ?: return@forEach
                listOfNotNull(Iou.hash(terms), terms.creditor.pubkey, terms.debtor.pubkey).filterTo(this) { it.startsWith(p) }
            }
        }.distinct()
    }

    companion object {
        const val GATEWAY_CARD_KEY = "sms-gateway-card"
    }
}

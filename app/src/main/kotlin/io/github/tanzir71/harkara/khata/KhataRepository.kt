package io.github.tanzir71.harkara.khata

import io.github.tanzir71.harkara.core.khata.Iou
import io.github.tanzir71.harkara.core.khata.IouBundle
import io.github.tanzir71.harkara.core.khata.IouConfirm
import io.github.tanzir71.harkara.core.khata.IouStatus
import io.github.tanzir71.harkara.core.links.HarkaraLinks
import io.github.tanzir71.harkara.core.khata.IouState
import io.github.tanzir71.harkara.core.khata.IouTerms
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.khata.Settlement
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.IouEntity
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/** Which way the money goes, from this phone's point of view. */
enum class IouDirection { THEY_OWE_ME, I_OWE_THEM }

/**
 * Khata: IOUs both people sign. Each update travels as the whole bundle (terms + signatures +
 * settlements) gift-wrapped (NIP-59) to the other person and to ourselves, or face to face as a QR.
 * Phones merge bundles, so the order things arrive in doesn't matter.
 */
class KhataRepository(
    private val db: HarkaraDatabase,
    private val outbox: Outbox,
    private val pools: () -> List<RelayPool>,
    private val profile: () -> KeyPair,
    private val displayName: () -> String? = { null },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val now get() = nowMillis() / 1000

    fun ious(): Flow<List<IouState>> = db.iouDao().observeAll().map { rows ->
        rows.mapNotNull { runCatching { Iou.state(IouBundle.fromJson(it.bundleJson)) }.getOrNull() }
    }

    suspend fun get(id: String): IouState? = db.iouDao().get(id)?.let { Iou.state(IouBundle.fromJson(it.bundleJson)) }

    /** Gift wraps addressed to us, on every pool (relays and LAN). */
    fun subscribe() {
        val me = profile().pubkeyHex
        pools().forEach { it.subscribe(SUB_KHATA, listOf(Filter(kinds = listOf(Kinds.GIFT_WRAP), tags = mapOf("p" to listOf(me)), since = now - 7 * 86_400))) }
    }

    fun startIngest(scope: CoroutineScope, pool: RelayPool) {
        scope.launch {
            pool.incoming.collect {
                if (it.event.kind == Kinds.IOU_SIGN) ingestEvent(it.event) else ingestWrap(it.event)
            }
        }
    }

    /**
     * Web confirmations (M10): someone without a key confirmed one of our IOUs on the o/ page, which publishes
     * a kind-3474 event with the code to public relays. Follows the hashes of our IOUs still waiting for one.
     */
    suspend fun subscribeConfirmations() {
        val hashes = db.iouDao().all().mapNotNull { row ->
            runCatching { Iou.state(IouBundle.fromJson(row.bundleJson)) }.getOrNull()
                ?.takeIf { it.status == IouStatus.CLAIMED && it.terms.confirmCodeHash != null }?.let { Iou.hash(it.terms) }
        }
        if (hashes.isEmpty()) return
        pools().forEach { it.subscribe(SUB_CONFIRM, listOf(Filter(kinds = listOf(Kinds.IOU_SIGN), tags = mapOf("h" to hashes.take(200))))) }
    }

    /**
     * A bare IOU signature or settlement (kind 3474/3475) that came by SMS, QR, link or relay: merged into the
     * IOU with the same terms hash, if this phone has it. Returns true when it changed our view.
     */
    suspend fun ingestEvent(event: Event): Boolean {
        if (event.kind != Kinds.IOU_SIGN && event.kind != Kinds.SETTLE) return false
        val hash = event.tagValue("h") ?: return false
        val bundle = db.iouDao().all().firstNotNullOfOrNull { row ->
            runCatching { IouBundle.fromJson(row.bundleJson) }.getOrNull()?.takeIf { Iou.hash(it.terms) == hash }
        } ?: return false
        val incoming = if (event.kind == Kinds.IOU_SIGN) IouBundle(bundle.terms, signatures = listOf(event.toJson())) else IouBundle(bundle.terms, settlements = listOf(event.toJson()))
        return merge(incoming)
    }

    /** Opens a gift wrap; returns true when it carried an IOU bundle that changed our view. */
    suspend fun ingestWrap(wrap: Event): Boolean {
        if (wrap.kind != Kinds.GIFT_WRAP) return false
        val rumor = runCatching { GiftWrap.unwrap(wrap, profile()) }.getOrNull() ?: return false
        if (rumor.kind != Kinds.IOU) return false
        val bundle = runCatching { IouBundle.fromJson(rumor.content) }.getOrNull() ?: return false
        val parties = listOfNotNull(bundle.terms.creditor.pubkey, bundle.terms.debtor.pubkey)
        if (rumor.pubkey !in parties) return false
        return merge(bundle)
    }

    /** Merges [bundle] into storage. Returns true if anything new was learned. */
    suspend fun merge(bundle: IouBundle): Boolean {
        val me = profile().pubkeyHex
        val terms = bundle.terms
        if (me != terms.creditor.pubkey && me != terms.debtor.pubkey) return false
        val existing = db.iouDao().get(terms.id)?.let { IouBundle.fromJson(it.bundleJson) }
        if (existing != null && Iou.hash(existing.terms) != Iou.hash(terms)) return false
        val merged = existing?.merge(bundle) ?: bundle.merge(bundle)
        if (existing != null && merged == existing) return false
        save(merged)
        return true
    }

    private suspend fun save(bundle: IouBundle) {
        val me = profile().pubkeyHex
        val other = listOfNotNull(bundle.terms.creditor.pubkey, bundle.terms.debtor.pubkey).firstOrNull { it != me }
        val old = db.iouDao().get(bundle.terms.id)
        db.iouDao().upsert(IouEntity(bundle.terms.id, bundle.toJson(), other, old?.createdAt ?: bundle.terms.createdAt, nowMillis()))
    }

    /** Sends the bundle to the other person (if they have a key) and to ourselves. */
    private suspend fun send(bundle: IouBundle) {
        val keys = profile()
        val other = listOfNotNull(bundle.terms.creditor.pubkey, bundle.terms.debtor.pubkey).filter { it != keys.pubkeyHex }
        val rumor = GiftWrap.createRumor(keys.pubkeyHex, Kinds.IOU, bundle.toJson(), listOf(listOf("iou", bundle.terms.id)), now)
        GiftWrap.wrapForGroup(rumor, keys, other).forEach { outbox.enqueue(it, Priority.TASK) }
    }

    /** Proposes an IOU and signs it ourselves (it's a claim until the other side signs). */
    suspend fun create(direction: IouDirection, other: Party, amount: Long, note: String): IouState {
        val me = Party(displayName() ?: "Me", profile().pubkeyHex)
        val id = UUID.randomUUID().toString()
        val terms = IouTerms(
            id = id,
            creditor = if (direction == IouDirection.THEY_OWE_ME) me else other,
            debtor = if (direction == IouDirection.THEY_OWE_ME) other else me,
            amount = amount,
            note = note.trim(),
            createdAt = now,
            // Someone without a key confirms on the web page with a one-time code from their link (M10).
            confirmCodeHash = if (other.pubkey == null) Iou.codeHash(IouConfirm.code(profile(), id)) else null,
        )
        val bundle = IouBundle(terms, listOf(Iou.sign(profile(), terms, now).toJson()))
        save(bundle)
        send(bundle)
        return Iou.state(bundle)
    }

    /** Split a bill in a group: one IOU per member for their exact share. */
    suspend fun splitBill(members: List<Party>, total: Long, note: String): List<IouState> =
        members.zip(Iou.split(total, members.size)).map { (member, share) -> create(IouDirection.THEY_OWE_ME, member, share, note) }

    suspend fun sign(state: IouState): IouState {
        val bundle = state.bundle.merge(IouBundle(state.terms, listOf(Iou.sign(profile(), state.terms, now).toJson())))
        save(bundle)
        send(bundle)
        return Iou.state(bundle)
    }

    suspend fun recordSettlement(state: IouState, settlement: Settlement): IouState {
        val bundle = state.bundle.merge(IouBundle(state.terms, settlements = listOf(Iou.settle(profile(), state.terms, settlement, now).toJson())))
        save(bundle)
        send(bundle)
        return Iou.state(bundle)
    }

    /** Signs the settlement the other side recorded. */
    suspend fun confirmSettlement(state: IouState): IouState {
        val s = state.settlement ?: return state
        return recordSettlement(state, s)
    }

    /** QR handshake: show this, the other phone scans it, signs, and shows its QR back. */
    fun qrFor(state: IouState): String = Iou.toQr(state.bundle)

    /** Reads a scanned (or pasted) IOU QR. Returns the merged state, or null if it isn't for us. */
    suspend fun importQr(text: String): IouState? {
        val bundle = Iou.fromQr(text.trim()) ?: return null
        merge(bundle)
        return get(bundle.terms.id)
    }

    /** Our own signature on [state] (kind 3474), e.g. to send it by SMS. */
    fun mySignature(state: IouState): Event? = state.bundle.signatureEvents().firstOrNull { it.pubkey == profile().pubkeyHex && Iou.verifySignature(it, state.terms) }

    /** Our latest settlement record on [state] (kind 3475). */
    fun mySettlement(state: IouState): Event? =
        state.bundle.settlementEvents().filter { it.pubkey == profile().pubkeyHex && Iou.verifySettlement(it, state.terms) }.maxByOrNull { it.createdAt }

    /**
     * The link in an SMS reminder: for someone without a key, the o/ page confirm link
     * (https://tanzir71.github.io/harkara/o/#c:…); otherwise (or for IOUs made before M10) the help page.
     */
    fun reminderLink(state: IouState): String {
        val sig = mySignature(state) ?: return HELP_URL
        val code = IouConfirm.code(profile(), state.terms.id)
        if (state.terms.confirmCodeHash != Iou.codeHash(code)) return HELP_URL
        return runCatching { HarkaraLinks.web(IouConfirm.PREFIX + IouConfirm.encode(state.terms, sig, code)) }.getOrDefault(HELP_URL)
    }

    companion object {
        const val SUB_KHATA = "harkara-khata"
        const val SUB_CONFIRM = "harkara-khata-confirm"
        /** Fallback reminder link: what an IOU is, for people with the app or older IOUs. */
        const val HELP_URL = "https://github.com/tanzir71/harkara/blob/main/docs/IOU.md"
    }
}

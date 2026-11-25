package io.github.tanzir71.harkara.core.khata

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

/** One side of an IOU: a Harkara key, or just a name for someone who doesn't use the app yet. */
@Serializable
data class Party(val name: String, val pubkey: String? = null)

/**
 * The terms both people sign. [amount] is whole taka. The IOU is agreed only when both parties' keys
 * have signed [Iou.hash] of exactly these terms.
 */
@Serializable
data class IouTerms(
    val id: String,
    val creditor: Party,
    val debtor: Party,
    val amount: Long,
    val currency: String = "BDT",
    val note: String = "",
    val createdAt: Long,
    val due: Long? = null,
    /** Set when the IOU is a receipt for a paid task (`30402:<requester>:<d>`). */
    val task: String? = null,
)

/** Everything known about one IOU: the terms plus every signature and settlement seen. Carried as one blob. */
@Serializable
data class IouBundle(
    val terms: IouTerms,
    val signatures: List<String> = emptyList(),
    val settlements: List<String> = emptyList(),
) {
    fun signatureEvents(): List<Event> = signatures.mapNotNull { runCatching { Event.fromJson(it) }.getOrNull() }
    fun settlementEvents(): List<Event> = settlements.mapNotNull { runCatching { Event.fromJson(it) }.getOrNull() }

    /** Union of two views of the same IOU (same terms hash); anything that doesn't verify is left out. */
    fun merge(other: IouBundle): IouBundle {
        require(Iou.hash(terms) == Iou.hash(other.terms)) { "different IOUs" }
        val sigs = (signatureEvents() + other.signatureEvents()).filter { Iou.verifySignature(it, terms) }.distinctBy { it.pubkey }
        val settles = (settlementEvents() + other.settlementEvents()).filter { Iou.verifySettlement(it, terms) }.distinctBy { it.id }
        return copy(signatures = sigs.map { it.toJson() }, settlements = settles.map { it.toJson() })
    }

    fun toJson(): String = Iou.json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): IouBundle = Iou.json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class Settlement(val method: String, val amount: Long, val trxId: String = "")

enum class IouStatus {
    /** Only one side has signed: it's a claim, not yet an agreement. */
    CLAIMED,
    AGREED,
    /** Agreed, and one side has recorded a settlement the other hasn't confirmed. */
    SETTLING,
    SETTLED,
}

data class IouState(
    val bundle: IouBundle,
    val status: IouStatus,
    val signedBy: Set<String>,
    val settlement: Settlement? = null,
) {
    val terms: IouTerms get() = bundle.terms
    fun waitingFor(me: String): Boolean = status == IouStatus.CLAIMED && me !in signedBy && me in listOfNotNull(terms.creditor.pubkey, terms.debtor.pubkey)
}

object Iou {
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    const val QR_PREFIX = "harkara:iou:"

    /** SHA-256 of the canonical terms JSON (fixed field order, defaults included). */
    fun hash(terms: IouTerms): String = sha256Hex(json.encodeToString(IouTerms.serializer(), terms))

    /** Signs the terms hash with [keys] (kind 3474). Only a party to the IOU may sign. */
    fun sign(keys: KeyPair, terms: IouTerms, at: Long): Event {
        require(keys.pubkeyHex == terms.creditor.pubkey || keys.pubkeyHex == terms.debtor.pubkey) { "not a party to this IOU" }
        return keys.signEvent(Kinds.IOU_SIGN, listOf(listOf("h", hash(terms)), listOf("iou", terms.id)), "", at)
    }

    fun verifySignature(e: Event, terms: IouTerms): Boolean =
        e.kind == Kinds.IOU_SIGN && e.tagValue("h") == hash(terms) &&
            (e.pubkey == terms.creditor.pubkey || e.pubkey == terms.debtor.pubkey) && e.verify()

    fun settlementHash(terms: IouTerms, s: Settlement): String = sha256Hex(hash(terms) + "|" + json.encodeToString(Settlement.serializer(), s))

    /** One side's record of a settlement (kind 3475); settled when both sides sign the same one. */
    fun settle(keys: KeyPair, terms: IouTerms, s: Settlement, at: Long): Event {
        require(keys.pubkeyHex == terms.creditor.pubkey || keys.pubkeyHex == terms.debtor.pubkey) { "not a party to this IOU" }
        return keys.signEvent(
            Kinds.SETTLE,
            listOf(listOf("h", hash(terms)), listOf("s", settlementHash(terms, s)), listOf("iou", terms.id)),
            json.encodeToString(Settlement.serializer(), s),
            at,
        )
    }

    fun verifySettlement(e: Event, terms: IouTerms): Boolean {
        if (e.kind != Kinds.SETTLE || e.tagValue("h") != hash(terms)) return false
        if (e.pubkey != terms.creditor.pubkey && e.pubkey != terms.debtor.pubkey) return false
        val s = runCatching { json.decodeFromString(Settlement.serializer(), e.content) }.getOrNull() ?: return false
        return e.tagValue("s") == settlementHash(terms, s) && e.verify()
    }

    /**
     * The state everyone computes from the same bundle. With a counterparty who has no key (not on
     * Harkara yet), one signature is all there can be, so it stays CLAIMED until they confirm on the web.
     */
    fun state(bundle: IouBundle): IouState {
        val terms = bundle.terms
        val sigs = bundle.signatureEvents().filter { verifySignature(it, terms) }
        val signedBy = sigs.map { it.pubkey }.toSet()
        val parties = listOfNotNull(terms.creditor.pubkey, terms.debtor.pubkey).toSet()
        val agreed = parties.size == 2 && signedBy.containsAll(parties)
        if (!agreed) return IouState(bundle, IouStatus.CLAIMED, signedBy)
        val settles = bundle.settlementEvents().filter { verifySettlement(it, terms) }
        val byHash = settles.groupBy { it.tagValue("s") }
        val both = byHash.values.firstOrNull { group -> group.map { it.pubkey }.toSet().containsAll(parties) }
        if (both != null) return IouState(bundle, IouStatus.SETTLED, signedBy, parse(both.first()))
        val any = settles.maxByOrNull { it.createdAt }
        return if (any != null) IouState(bundle, IouStatus.SETTLING, signedBy, parse(any)) else IouState(bundle, IouStatus.AGREED, signedBy)
    }

    private fun parse(e: Event): Settlement? = runCatching { json.decodeFromString(Settlement.serializer(), e.content) }.getOrNull()

    /** QR handshake payload: the whole bundle, base64url, for in-person co-signing with no network. */
    fun toQr(bundle: IouBundle): String = QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bundle.toJson().encodeToByteArray())

    fun fromQr(text: String): IouBundle? = runCatching {
        require(text.startsWith(QR_PREFIX))
        IouBundle.fromJson(Base64.getUrlDecoder().decode(text.removePrefix(QR_PREFIX)).decodeToString())
    }.getOrNull()

    /** Splits a bill equally; the first members absorb the remainder taka so the total is exact. */
    fun split(total: Long, members: Int): List<Long> {
        require(members > 0 && total >= 0)
        val base = total / members
        val extra = (total % members).toInt()
        return List(members) { i -> base + if (i < extra) 1 else 0 }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

/** CSV and JSON export of the ledger (liteiou's export formats). */
object KhataExport {
    fun csv(states: List<IouState>, me: String): String = buildString {
        appendLine("id,date,direction,counterparty,amount,currency,note,status,settled_by,trxid")
        for (s in states.sortedBy { it.terms.createdAt }) {
            val t = s.terms
            val incoming = t.creditor.pubkey == me
            val other = if (incoming) t.debtor else t.creditor
            val date = java.time.Instant.ofEpochSecond(t.createdAt).toString().take(10)
            appendLine(
                listOf(t.id, date, if (incoming) "lent" else "borrowed", other.name, t.amount.toString(), t.currency, t.note, s.status.name.lowercase(), s.settlement?.method.orEmpty(), s.settlement?.trxId.orEmpty())
                    .joinToString(",") { field -> if (field.any { it == ',' || it == '"' || it == '\n' }) "\"" + field.replace("\"", "\"\"") + "\"" else field },
            )
        }
    }

    fun json(states: List<IouState>): String = Iou.json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(IouBundle.serializer()),
        states.map { it.bundle },
    )
}

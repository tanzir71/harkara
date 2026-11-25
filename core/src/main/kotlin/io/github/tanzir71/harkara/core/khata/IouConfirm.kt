package io.github.tanzir71.harkara.core.khata

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.EventId
import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.wire.Text8
import io.github.tanzir71.harkara.core.wire.Varint
import java.io.ByteArrayOutputStream
import java.util.Base64

/** A decoded confirm link: the terms, the keyed party's signature over their hash, and the one-time code. */
data class IouConfirmLink(val terms: IouTerms, val signature: Event, val code: String) {
    /** True when the code matches the terms' commitment. */
    val codeMatches: Boolean get() = terms.confirmCodeHash != null && Iou.codeHash(code) == terms.confirmCodeHash
}

/**
 * The IOU link for someone without Harkara (`https://tanzir71.github.io/harkara/o/#c:<payload>`). It carries
 * the terms field by field, the keyed party's kind-3474 signature (its tags are rebuilt: `h` = hash of the
 * terms JSON, `iou` = id) and the one-time code the terms committed to. The reader rebuilds the canonical
 * terms JSON (field order of [IouTerms], kotlinx/`JSON.stringify` string escaping, `confirmCodeHash` =
 * SHA-256 of the code), hashes it and checks the signature; the web page does the same in the browser. A
 * "confirm" there is a kind-3474 event by a throwaway key with `["code", code]` ([Iou.verifyCodeConfirmation]).
 *
 * ```
 * u8      version (1)
 * u8      flags: 0x01 creditor signed (else debtor), 0x02 id is a UUID (16 bytes), 0x04 due, 0x08 task,
 *         0x10 currency other than BDT
 * 8       code (16 lowercase hex characters)
 * u32 BE  created_at of the signature
 * 64      signature
 * 32      signer's x-only pubkey
 * id      16 bytes, or a string
 * varint  amount
 * u32 BE  terms createdAt
 * varint  due            (flag 0x04)
 * string  task           (flag 0x08)
 * string  currency       (flag 0x10)
 * string  creditor name, debtor name, note
 * ```
 * Strings are varint length + Text8 bytes. Adding a field to [IouTerms] needs a new version here.
 */
object IouConfirm {
    const val PREFIX = "c:"
    private const val VERSION = 1
    private const val F_CREDITOR_SIGNED = 0x01
    private const val F_UUID = 0x02
    private const val F_DUE = 0x04
    private const val F_TASK = 0x08
    private const val F_CURRENCY = 0x10
    private const val MAX_STRING = 1024
    private val b64url = Base64.getUrlEncoder().withoutPadding()

    /**
     * The one-time code for IOU [iouId], derived from the creator's key so the phone can rebuild the link at
     * any time (and after a restore) without storing it: HMAC-SHA256(private key, "harkara/iou-confirm/" + id),
     * first 8 bytes as hex.
     */
    fun code(keys: KeyPair, iouId: String): String =
        Hex.encode(Hashes.hmacSha256(keys.privateKey, "harkara/iou-confirm/$iouId".encodeToByteArray()).copyOf(8))

    /** Base64url payload for [terms] and the keyed party's [signature]. */
    fun encode(terms: IouTerms, signature: Event, code: String): String {
        require(Iou.verifySignature(signature, terms)) { "not a valid signature over these terms" }
        require(signature.tags == listOf(listOf("h", Iou.hash(terms)), listOf("iou", terms.id)) && signature.content.isEmpty()) { "unexpected signature shape" }
        require(terms.confirmCodeHash == Iou.codeHash(code)) { "the terms do not commit to this code" }
        return encodeRaw(terms, signature.createdAt, signature.sig, code, signature.pubkey)
    }

    /** The byte layout without the checks (tests build tampered links with it). */
    internal fun encodeRaw(terms: IouTerms, createdAt: Long, sig: String, code: String, signer: String): String {
        require(Hex.isLowerHex(code, 8)) { "code must be 16 lowercase hex characters" }
        val creditorSigned = terms.creditor.pubkey == signer
        require(creditorSigned || terms.debtor.pubkey == signer) { "signer is not a party" }
        require((if (creditorSigned) terms.debtor.pubkey else terms.creditor.pubkey) == null) { "a confirm link is for the party without a key" }
        val uuid = isUuid(terms.id)
        var flags = 0
        if (creditorSigned) flags = flags or F_CREDITOR_SIGNED
        if (uuid) flags = flags or F_UUID
        if (terms.due != null) flags = flags or F_DUE
        if (terms.task != null) flags = flags or F_TASK
        if (terms.currency != "BDT") flags = flags or F_CURRENCY
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        out.write(flags)
        out.write(Hex.decode(code))
        u32(out, createdAt)
        out.write(Hex.decode(sig))
        out.write(Hex.decode(signer))
        if (uuid) out.write(Hex.decode(terms.id.replace("-", ""))) else string(out, terms.id)
        Varint.write(out, terms.amount)
        u32(out, terms.createdAt)
        terms.due?.let { Varint.write(out, it) }
        terms.task?.let { string(out, it) }
        if (terms.currency != "BDT") string(out, terms.currency)
        string(out, terms.creditor.name)
        string(out, terms.debtor.name)
        string(out, terms.note)
        return b64url.encodeToString(out.toByteArray())
    }

    /** Decodes a payload; null when it is malformed or the signature does not verify. */
    fun decode(payload: String): IouConfirmLink? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(payload.trim())
        var pos = 0
        fun take(n: Int): ByteArray {
            require(n >= 0 && pos + n <= bytes.size) { "truncated" }
            return bytes.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun u8() = take(1)[0].toInt() and 0xff
        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val b = u8()
                v = v or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
                require(shift < 63)
            }
        }
        fun u32() = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()
        fun string(): String {
            val n = varint()
            require(n in 0..MAX_STRING.toLong())
            return Text8.decode(take(n.toInt()))
        }
        require(u8() == VERSION)
        val flags = u8()
        require(flags and 0x1f.inv() == 0)
        val code = Hex.encode(take(8))
        val createdAt = u32()
        val sig = Hex.encode(take(64))
        val signer = Hex.encode(take(32))
        val id = if (flags and F_UUID != 0) Hex.encode(take(16)).let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" } else string()
        val amount = varint()
        val termsCreatedAt = u32()
        val due = if (flags and F_DUE != 0) varint() else null
        val task = if (flags and F_TASK != 0) string() else null
        val currency = if (flags and F_CURRENCY != 0) string() else "BDT"
        val creditorName = string()
        val debtorName = string()
        val note = string()
        require(pos == bytes.size) { "trailing bytes" }
        val creditorSigned = flags and F_CREDITOR_SIGNED != 0
        val terms = IouTerms(
            id = id,
            creditor = Party(creditorName, signer.takeIf { creditorSigned }),
            debtor = Party(debtorName, signer.takeUnless { creditorSigned }),
            amount = amount,
            currency = currency,
            note = note,
            createdAt = termsCreatedAt,
            due = due,
            task = task,
            confirmCodeHash = Iou.codeHash(code),
        )
        val tags = listOf(listOf("h", Iou.hash(terms)), listOf("iou", terms.id))
        val signature = Event(EventId.compute(signer, createdAt, Kinds.IOU_SIGN, tags, ""), signer, createdAt, Kinds.IOU_SIGN, tags, "", sig)
        require(Iou.verifySignature(signature, terms))
        IouConfirmLink(terms, signature, code)
    }.getOrNull()

    /** What the web page signs for the party without a key (also used by tests and fixtures). */
    fun confirm(throwaway: KeyPair, terms: IouTerms, code: String, at: Long): Event =
        throwaway.signEvent(Kinds.IOU_SIGN, listOf(listOf("h", Iou.hash(terms)), listOf("iou", terms.id), listOf("code", code)), "", at)

    private fun u32(out: ByteArrayOutputStream, v: Long) {
        require(v in 0..0xFFFF_FFFFL) { "time does not fit in u32" }
        out.write((v ushr 24).toInt())
        out.write((v ushr 16).toInt())
        out.write((v ushr 8).toInt())
        out.write(v.toInt())
    }

    private fun string(out: ByteArrayOutputStream, s: String) {
        val b = Text8.encode(s)
        require(b.size <= MAX_STRING) { "string too long" }
        Varint.write(out, b.size.toLong())
        out.write(b)
    }

    private fun isUuid(s: String): Boolean = s.length == 36 && s.withIndex().all { (i, c) ->
        if (i == 8 || i == 13 || i == 18 || i == 23) c == '-' else c in '0'..'9' || c in 'a'..'f'
    }
}

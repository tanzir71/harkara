package io.github.tanzir71.harkara.core.tasks

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import org.bouncycastle.crypto.generators.SCrypt
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The offline handoff (handover §4): a requester-signed, single-use token (kind 3472) bound to one task
 * and one runner. The requester shows it as a QR (the whole signed token: the runner's phone checks the
 * signature with every radio off) or as 6 digits derived from it. For the digits, the requester committed
 * to a slow scrypt hash of the code in the `confirm` step, so the runner's phone can check a typed code
 * offline while guessing all million codes stays impractically slow.
 */
object Handoff {
    /** scrypt cost for the 6-digit commitment (~50–100 ms per guess on a budget phone). */
    var scryptN: Int = 1 shl 14

    fun token(requester: KeyPair, offer: TaskOffer, runner: String, createdAt: Long, nonce: String = randomNonce()): Event =
        requester.signEvent(
            Kinds.HANDOFF_TOKEN,
            listOf(listOf("a", offer.address), listOf("p", runner), listOf("nonce", nonce)),
            "",
            createdAt,
        )

    /** The 6 digits shown under the QR: unpredictable without the requester's signature. */
    fun code(token: Event): String {
        val h = MessageDigest.getInstance("SHA-256").digest(("harkara-handoff:" + token.sig).encodeToByteArray())
        val n = BigInteger(1, h).mod(BigInteger.valueOf(1_000_000)).toInt()
        return n.toString().padStart(6, '0')
    }

    /** "482 913" style for display. */
    fun displayCode(code: String) = code.take(3) + " " + code.drop(3)

    fun commitment(code: String, offerAddress: String, runner: String): String {
        val salt = ("harkara-handoff|$offerAddress|$runner").encodeToByteArray()
        return SCrypt.generate(code.encodeToByteArray(), salt, scryptN, 8, 1, 32).joinToString("") { "%02x".format(it) }
    }

    fun verifyCode(code: String, commitment: String, offer: TaskOffer, runner: String): Boolean =
        code.length == 6 && code.all(Char::isDigit) && commitment(code, offer.address, runner) == commitment

    /** Checks a scanned token offline: signed by the requester, for this task and this runner. */
    fun verifyToken(token: Event, offer: TaskOffer, runner: String): Boolean =
        token.kind == Kinds.HANDOFF_TOKEN &&
            token.pubkey == offer.requester &&
            token.tagValue("a") == offer.address &&
            token.tagValue("p") == runner &&
            token.verify()

    private fun randomNonce(): String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

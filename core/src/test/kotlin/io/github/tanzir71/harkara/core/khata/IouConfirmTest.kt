package io.github.tanzir71.harkara.core.khata

import io.github.tanzir71.harkara.core.nostr.KeyPair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IouConfirmTest {
    private val now = 1_764_000_000L
    private val tania = KeyPair.generate()
    private val code = IouConfirm.code(tania, "iou-7")
    private val terms = IouTerms(
        "iou-7", Party("Tania", tania.pubkeyHex), Party("Karim"), 1250, note = "চায়ের দোকানের বাকি", createdAt = now,
        confirmCodeHash = Iou.codeHash(code),
    )
    private val claim = IouBundle(terms, listOf(Iou.sign(tania, terms, now).toJson()))

    @Test
    fun `older IOUs keep their hash because a missing code hash is not written`() {
        val old = terms.copy(confirmCodeHash = null)
        val json = Iou.json.encodeToString(IouTerms.serializer(), old)
        assertFalse(json.contains("confirmCodeHash"))
        assertTrue(json.endsWith("\"due\":null,\"task\":null}"))
        assertTrue(Iou.json.encodeToString(IouTerms.serializer(), terms).contains("\"confirmCodeHash\":\"${Iou.codeHash(code)}\""))
    }

    @Test
    fun `the code is derived from the creator's key and the IOU id`() {
        assertEquals(code, IouConfirm.code(tania, "iou-7"))
        assertEquals(16, code.length)
        assertFalse(code == IouConfirm.code(tania, "iou-8"))
        assertFalse(code == IouConfirm.code(KeyPair.generate(), "iou-7"))
    }

    @Test
    fun `confirm link round trips and verifies the creator's signature`() {
        val payload = IouConfirm.encode(terms, Iou.state(claim).bundle.signatureEvents().single(), code)
        val link = IouConfirm.decode(payload)
        assertNotNull(link)
        assertEquals(terms, link!!.terms)
        assertEquals(Iou.hash(terms), Iou.hash(link.terms))
        assertTrue(link.codeMatches)
        assertEquals(claim.signatureEvents().single(), link.signature)

        val chars = payload.toCharArray()
        chars[chars.size / 2] = if (chars[chars.size / 2] == 'A') 'B' else 'A'
        assertNull(IouConfirm.decode(String(chars)))
        assertNull(IouConfirm.decode("not a payload"))
    }

    @Test
    fun `a web confirmation with the right code makes the IOU agreed`() {
        assertEquals(IouStatus.CLAIMED, Iou.state(claim).status)
        val browser = KeyPair.generate()
        val confirmation = IouConfirm.confirm(browser, terms, code, now + 300)
        assertTrue(Iou.verifyCodeConfirmation(confirmation, terms))
        val agreed = claim.merge(IouBundle(terms, listOf(confirmation.toJson())))
        assertEquals(IouStatus.AGREED, Iou.state(agreed).status)
        assertEquals(2, agreed.signatures.size)

        // Wrong code, or a confirmation for an IOU without a code hash, counts for nothing.
        val wrong = IouConfirm.confirm(browser, terms, "0000000000000000", now)
        assertFalse(Iou.verifyCodeConfirmation(wrong, terms))
        assertEquals(IouStatus.CLAIMED, Iou.state(claim.merge(IouBundle(terms, listOf(wrong.toJson())))).status)
        val noCode = terms.copy(confirmCodeHash = null)
        assertFalse(Iou.verifyCodeConfirmation(IouConfirm.confirm(browser, noCode, code, now), noCode))
        // The keyed party's own key cannot stand in for the other side.
        assertFalse(Iou.verifyCodeConfirmation(IouConfirm.confirm(tania, terms, code, now), terms))
    }

    @Test
    fun `without a key the creditor's settlement settles it, a debtor's own record does not`() {
        val agreed = claim.merge(IouBundle(terms, listOf(IouConfirm.confirm(KeyPair.generate(), terms, code, now).toJson())))
        val paid = agreed.copy(settlements = listOf(Iou.settle(tania, terms, Settlement("bkash", 1250, "9F4K2L7Q1A"), now + 900).toJson()))
        assertEquals(IouStatus.SETTLED, Iou.state(paid).status)

        val iOwe = IouTerms("iou-9", Party("Karim Store"), Party("Tania", tania.pubkeyHex), 300, createdAt = now, confirmCodeHash = Iou.codeHash(IouConfirm.code(tania, "iou-9")))
        val confirmed = IouBundle(iOwe, listOf(Iou.sign(tania, iOwe, now).toJson(), IouConfirm.confirm(KeyPair.generate(), iOwe, IouConfirm.code(tania, "iou-9"), now).toJson()))
        assertEquals(IouStatus.AGREED, Iou.state(confirmed).status)
        val claimedPaid = confirmed.copy(settlements = listOf(Iou.settle(tania, iOwe, Settlement("cash", 300), now).toJson()))
        assertEquals(IouStatus.SETTLING, Iou.state(claimedPaid).status)
    }
}

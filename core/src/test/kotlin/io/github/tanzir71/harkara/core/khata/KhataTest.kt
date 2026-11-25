package io.github.tanzir71.harkara.core.khata

import io.github.tanzir71.harkara.core.nostr.KeyPair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KhataTest {
    private val now = 1_764_000_000L
    private val me = KeyPair.generate()
    private val rafi = KeyPair.generate()
    private val terms = IouTerms("iou-1", Party("Me", me.pubkeyHex), Party("Rafi Hasan", rafi.pubkeyHex), 500, note = "Star Kabab lunch", createdAt = now)

    @Test
    fun `an IOU is only a claim until both people sign the same hash`() {
        val mine = IouBundle(terms, listOf(Iou.sign(me, terms, now).toJson()))
        assertEquals(IouStatus.CLAIMED, Iou.state(mine).status)
        assertTrue(Iou.state(mine).waitingFor(rafi.pubkeyHex))
        assertFalse(Iou.state(mine).waitingFor(me.pubkeyHex))

        val both = mine.merge(IouBundle(terms, listOf(Iou.sign(rafi, terms, now + 5).toJson())))
        assertEquals(IouStatus.AGREED, Iou.state(both).status)
        assertEquals(setOf(me.pubkeyHex, rafi.pubkeyHex), Iou.state(both).signedBy)
    }

    @Test
    fun `a signature over different terms does not count`() {
        val inflated = terms.copy(amount = 5000)
        assertNotEquals(Iou.hash(terms), Iou.hash(inflated))
        val bundle = IouBundle(terms, listOf(Iou.sign(me, terms, now).toJson(), Iou.sign(rafi, inflated, now).toJson()))
        assertEquals(IouStatus.CLAIMED, Iou.state(bundle).status)
        assertEquals(1, bundle.merge(bundle).signatures.size)
    }

    @Test
    fun `only the two parties can sign, and tampered signatures are dropped`() {
        assertThrows(IllegalArgumentException::class.java) { Iou.sign(KeyPair.generate(), terms, now) }
        val forged = Iou.sign(rafi, terms, now).copy(createdAt = now + 1)
        assertFalse(Iou.verifySignature(forged, terms))
        val bundle = IouBundle(terms, listOf(Iou.sign(me, terms, now).toJson(), forged.toJson()))
        assertEquals(IouStatus.CLAIMED, Iou.state(bundle).status)
    }

    @Test
    fun `settled only when both sign the same settlement`() {
        val agreed = IouBundle(terms, listOf(Iou.sign(me, terms, now).toJson(), Iou.sign(rafi, terms, now).toJson()))
        val s = Settlement("bkash", 500, "9F4K2L7Q")
        val rafiSays = agreed.copy(settlements = listOf(Iou.settle(rafi, terms, s, now + 60).toJson()))
        assertEquals(IouStatus.SETTLING, Iou.state(rafiSays).status)
        val disagree = rafiSays.copy(settlements = rafiSays.settlements + Iou.settle(me, terms, s.copy(trxId = "OTHER123"), now + 70).toJson())
        assertEquals(IouStatus.SETTLING, Iou.state(disagree).status)
        val settled = rafiSays.copy(settlements = rafiSays.settlements + Iou.settle(me, terms, s, now + 80).toJson())
        assertEquals(IouStatus.SETTLED, Iou.state(settled).status)
        assertEquals("9F4K2L7Q", Iou.state(settled).settlement?.trxId)
    }

    @Test
    fun `IOU with someone not on Harkara stays a claim`() {
        val store = terms.copy(id = "iou-2", creditor = Party("Karim Store"), debtor = Party("Me", me.pubkeyHex), amount = 300)
        val bundle = IouBundle(store, listOf(Iou.sign(me, store, now).toJson()))
        assertEquals(IouStatus.CLAIMED, Iou.state(bundle).status)
    }

    @Test
    fun `QR handshake round trip with no network`() {
        val proposal = IouBundle(terms, listOf(Iou.sign(me, terms, now).toJson()))
        val scanned = Iou.fromQr(Iou.toQr(proposal))!!
        val countersigned = scanned.merge(IouBundle(terms, listOf(Iou.sign(rafi, terms, now + 3).toJson())))
        val back = Iou.fromQr(Iou.toQr(countersigned))!!
        assertEquals(IouStatus.AGREED, Iou.state(proposal.merge(back)).status)
        assertNull(Iou.fromQr("harkara:iou:not-base64!"))
        assertTrue(Iou.toQr(countersigned).length < 2500)
    }

    @Test
    fun `split bill gives one exact share per member`() {
        assertEquals(listOf(334L, 333L, 333L), Iou.split(1000, 3))
        assertEquals(1850L, Iou.split(1850, 4).sum())
    }

    @Test
    fun `csv and json export`() {
        val agreed = Iou.state(IouBundle(terms.copy(note = "Lunch, with \"extra\" naan"), emptyList()))
        val csv = KhataExport.csv(listOf(agreed), me.pubkeyHex)
        assertTrue(csv.lines()[0].startsWith("id,date,direction"))
        assertTrue(csv.contains("\"Lunch, with \"\"extra\"\" naan\""))
        assertTrue(csv.contains(",lent,Rafi Hasan,500,BDT,"))
        assertTrue(KhataExport.json(listOf(agreed)).contains("\"creditor\""))
    }
}

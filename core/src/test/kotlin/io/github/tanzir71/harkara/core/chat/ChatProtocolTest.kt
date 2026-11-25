package io.github.tanzir71.harkara.core.chat

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatProtocolTest {
    private val alice = KeyPair.generate()
    private val bob = KeyPair.generate()
    private val carol = KeyPair.generate()
    private val now = 1_764_000_000L

    @Test
    fun `room id is the member set, in any order`() {
        val a = ChatProtocol.roomId(listOf(alice.pubkeyHex, bob.pubkeyHex, carol.pubkeyHex))
        val b = ChatProtocol.roomId(setOf(carol.pubkeyHex, alice.pubkeyHex, bob.pubkeyHex, bob.pubkeyHex))
        assertEquals(a, b)
        assertEquals(32, a.length)
        assertTrue(a != ChatProtocol.roomId(listOf(alice.pubkeyHex, bob.pubkeyHex)))
    }

    @Test
    fun `group text with subject, name and card survives the gift wrap`() {
        val card = ChatCard.CheckIn("I'm safe", "Home · 21:40")
        val rumor = ChatProtocol.textRumor(
            alice.pubkeyHex, listOf(bob.pubkeyHex, carol.pubkeyHex), card.summary(), now,
            subject = "Road 7 building", name = "Ma", card = card,
        )
        val wraps = GiftWrap.wrapForGroup(rumor, alice, listOf(bob.pubkeyHex, carol.pubkeyHex))
        assertEquals(3, wraps.size)
        val opened = GiftWrap.unwrap(wraps.first { it.tagValue("p") == carol.pubkeyHex }, carol)
        val parsed = ChatProtocol.parse(opened) as ChatRumor.Text
        assertEquals(setOf(alice.pubkeyHex, bob.pubkeyHex, carol.pubkeyHex), parsed.members)
        assertEquals("Road 7 building", parsed.subject)
        assertEquals("Ma", parsed.name)
        assertEquals(card, parsed.card)
        assertEquals(rumor.id, parsed.rumor.id)
    }

    @Test
    fun `receipts name the messages and their status`() {
        val r = ChatProtocol.receiptRumor(bob.pubkeyHex, alice.pubkeyHex, listOf("aa", "bb", "aa"), ReceiptStatus.READ, now)
        assertEquals(Kinds.RECEIPT, r.kind)
        val parsed = ChatProtocol.parse(GiftWrap.unwrap(GiftWrap.sealAndWrap(r, bob, alice.pubkeyHex), alice)) as ChatRumor.Receipt
        assertEquals(listOf("aa", "bb"), parsed.messageIds)
        assertEquals(ReceiptStatus.READ, parsed.status)
        assertNull(ChatProtocol.parse(GiftWrap.createRumor(bob.pubkeyHex, Kinds.RECEIPT, "", listOf(listOf("e", "aa")), now)))
    }

    @Test
    fun `names never carry phone numbers`() {
        assertEquals(ChatVerdict.OK, ChatProtocol.checkName("Sumon"))
        assertEquals(ChatVerdict.OK, ChatProtocol.checkName("Road 7 Ma"))
        assertEquals(ChatVerdict.PHONE_NUMBER, ChatProtocol.checkName("Sumon 01711-234567"))
        assertEquals(ChatVerdict.PHONE_NUMBER, ChatProtocol.checkName("সুমন ০১৭১১২৩৪৫৬৭"))
        assertEquals(ChatVerdict.EMPTY, ChatProtocol.checkName("  "))
        assertEquals(ChatVerdict.TOO_LONG, ChatProtocol.checkName("x".repeat(33)))
        // A name with a number is never put on the wire, and an incoming one is not shown.
        val rumor = ChatProtocol.textRumor(alice.pubkeyHex, listOf(bob.pubkeyHex), "hi", now, name = "call 01711234567")
        assertNull(rumor.tagValue(ChatProtocol.NAME_TAG))
        assertNull(ChatProtocol.nameOf(listOf(listOf("n", "01711 234 567"))))
    }

    @Test
    fun `public rooms are signed kind 20000 with a day of life`() {
        val area = ChatProtocol.areaMessage(alice, "wh0r0", "Water at the mosque tap", now, name = "Rafi")
        assertTrue(area.validate())
        assertEquals(Kinds.AREA_CHAT, area.kind)
        assertEquals("wh0r0", area.tagValue("g"))
        assertEquals((now + 86_400).toString(), area.tagValue("expiration"))
        assertTrue(ChatProtocol.isAreaMessage(area))
        assertFalse(ChatProtocol.isWifiMessage(area))
        assertFalse(ChatProtocol.carriedByLanRoom(area))

        val wifi = ChatProtocol.wifiMessage(bob, "Generator on in the hall", now)
        assertTrue(ChatProtocol.isWifiMessage(wifi))
        assertFalse(ChatProtocol.isAreaMessage(wifi))
        assertTrue(ChatProtocol.carriedByLanRoom(wifi))
        assertTrue(ChatProtocol.carriedByLanRoom(GiftWrap.sealAndWrap(ChatProtocol.textRumor(alice.pubkeyHex, listOf(bob.pubkeyHex), "x", now), alice, bob.pubkeyHex)))

        assertEquals(ChatVerdict.PHONE_NUMBER, ChatProtocol.checkPublicText("call me 01711234567"))
        assertEquals(ChatVerdict.OK, ChatProtocol.checkPublicText("Road 7, house 12"))
    }

    @Test
    fun `the longest allowed text still fits a gift wrap`() {
        val bangla = "আ".repeat(ChatProtocol.MAX_TEXT_BYTES / 3)
        assertEquals(ChatVerdict.OK, ChatProtocol.checkText(bangla))
        assertEquals(ChatVerdict.TOO_LONG, ChatProtocol.checkText(bangla + "আ"))
        val rumor = ChatProtocol.textRumor(alice.pubkeyHex, listOf(bob.pubkeyHex), bangla, now, subject = "x".repeat(80), name = "Sumon")
        val wrap = GiftWrap.sealAndWrap(rumor, alice, bob.pubkeyHex)
        assertTrue(wrap.jsonSize() < Event.MAX_JSON_BYTES, "wrap is ${wrap.jsonSize()} bytes")
        assertTrue(wrap.validate())
    }
}

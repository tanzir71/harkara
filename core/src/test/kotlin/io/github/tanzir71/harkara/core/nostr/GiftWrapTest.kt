package io.github.tanzir71.harkara.core.nostr

import io.github.tanzir71.harkara.core.TestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GiftWrapTest {
    @Test
    fun `opens the NIP-59 example wrap`() {
        val recipient = KeyPair(Bech32.decodeNsec(NipExamples.NIP59_RECIPIENT_NSEC))
        val author = KeyPair(Bech32.decodeNsec(NipExamples.NIP59_AUTHOR_NSEC))

        val rumor = GiftWrap.unwrap(Event.fromJson(NipExamples.NIP59_WRAP), recipient)

        assertEquals("Are you going to the party tonight?", rumor.content)
        assertEquals(1, rumor.kind)
        assertEquals(author.pubkeyHex, rumor.pubkey)
        assertEquals(NipExamples.NIP59_RUMOR_ID, rumor.id)
        assertEquals(recipient.pubkeyHex, Event.fromJson(NipExamples.NIP59_WRAP).tagValue("p"))
    }

    @Test
    fun `opens both NIP-17 example wraps`() {
        val sender = KeyPair(Bech32.decodeNsec(NipExamples.NIP17_SENDER_NSEC))
        val receiver = KeyPair(Bech32.decodeNsec(NipExamples.NIP17_RECEIVER_NSEC))

        val toReceiver = GiftWrap.unwrap(Event.fromJson(NipExamples.NIP17_WRAP_TO_RECEIVER), receiver)
        val toSelf = GiftWrap.unwrap(Event.fromJson(NipExamples.NIP17_WRAP_TO_SENDER), sender)

        listOf(toReceiver, toSelf).forEach { rumor ->
            assertEquals(Kinds.CHAT_MESSAGE, rumor.kind)
            assertEquals("Hola, que tal?", rumor.content)
            assertEquals(sender.pubkeyHex, rumor.pubkey)
            assertEquals(listOf(receiver.pubkeyHex), rumor.tagValues("p"))
        }
        assertEquals(toReceiver.id, toSelf.id)
    }

    @Test
    fun `dm round trip with randomised timestamps and a one-time wrapper key`() {
        val alice = TestData.keys(11)
        val bob = TestData.keys(12)
        val now = nowSeconds()
        val rumor = GiftWrap.chatMessage(alice.pubkeyHex, listOf(bob.pubkeyHex), "Meet near the clinic at 8", createdAt = now)

        val seal = GiftWrap.seal(rumor, alice, bob.pubkeyHex)
        val wrap = GiftWrap.wrap(seal, bob.pubkeyHex)

        assertEquals(Kinds.SEAL, seal.kind)
        assertTrue(seal.tags.isEmpty())
        assertEquals(alice.pubkeyHex, seal.pubkey)
        assertEquals(Kinds.GIFT_WRAP, wrap.kind)
        assertEquals(bob.pubkeyHex, wrap.tagValue("p"))
        assertNotEquals(alice.pubkeyHex, wrap.pubkey)
        assertTrue(wrap.validate())
        listOf(seal.createdAt, wrap.createdAt).forEach { t ->
            assertTrue(t <= now && t >= now - GiftWrap.MAX_TIME_TWEAK_SECONDS - 1, "timestamp $t")
        }
        val opened = GiftWrap.unwrap(wrap, bob)
        assertEquals(rumor, opened)
        assertEquals(now, opened.createdAt)
    }

    @Test
    fun `only the recipient can open a wrap`() {
        val wrap = GiftWrap.sealAndWrap(GiftWrap.createRumor(TestData.keys(1).pubkeyHex, Kinds.CHAT_MESSAGE, "secret"), TestData.keys(1), TestData.keys(2).pubkeyHex)

        assertThrows(GiftWrapException::class.java) { GiftWrap.unwrap(wrap, TestData.keys(3)) }
    }

    @Test
    fun `a seal whose signer did not write the rumor is refused`() {
        val mallory = TestData.keys(20)
        val alice = TestData.keys(21)
        val bob = TestData.keys(22)
        // Mallory seals a rumor that claims to be from Alice.
        val forged = GiftWrap.createRumor(alice.pubkeyHex, Kinds.CHAT_MESSAGE, "send money to Mallory")
        val key = Nip44.conversationKey(mallory.privateKey, bob.pubkeyHex)
        val seal = UnsignedEvent(mallory.pubkeyHex, nowSeconds(), Kinds.SEAL, emptyList(), Nip44.encrypt(forged.toJson(), key)).sign(mallory)
        val wrap = GiftWrap.wrap(seal, bob.pubkeyHex)

        val error = assertThrows(GiftWrapException::class.java) { GiftWrap.unwrap(wrap, bob) }
        assertEquals("seal signer is not the rumor author", error.message)
    }

    @Test
    fun `tampered wraps and non-wraps are refused`() {
        val wrap = GiftWrap.sealAndWrap(GiftWrap.createRumor(TestData.keys(1).pubkeyHex, Kinds.CHAT_MESSAGE, "x"), TestData.keys(1), TestData.keys(2).pubkeyHex)

        assertThrows(GiftWrapException::class.java) { GiftWrap.unwrap(wrap.copy(content = wrap.content.reversed()), TestData.keys(2)) }
        assertThrows(GiftWrapException::class.java) { GiftWrap.unwrap(TestData.post(TestData.keys(1)), TestData.keys(2)) }
    }

    @Test
    fun `group send makes one wrap per member plus one to self`() {
        val sender = TestData.keys(30)
        val members = listOf(TestData.keys(31), TestData.keys(32), TestData.keys(33))
        val rumor = GiftWrap.chatMessage(sender.pubkeyHex, members.map { it.pubkeyHex }, "Flood water at the gate, use the back road")

        val wraps = GiftWrap.wrapForGroup(rumor, sender, members.map { it.pubkeyHex } + members[0].pubkeyHex)

        assertEquals(4, wraps.size)
        assertEquals((members + sender).map { it.pubkeyHex }.toSet(), wraps.map { it.tagValue("p") }.toSet())
        assertEquals(4, wraps.map { it.pubkey }.toSet().size, "every wrap has its own one-time key")
        (members + sender).forEach { member ->
            val wrap = wraps.single { it.tagValue("p") == member.pubkeyHex }
            assertEquals(rumor.id, GiftWrap.unwrap(wrap, member).id)
        }
    }

    @Test
    fun `ephemeral wraps for live data`() {
        val wrap = GiftWrap.sealAndWrap(
            GiftWrap.createRumor(TestData.keys(1).pubkeyHex, Kinds.LIVE_LOCATION, "23.7461,90.3742", listOf(listOf("seq", "4"), listOf("end", "1795590000"))),
            TestData.keys(1),
            TestData.keys(2).pubkeyHex,
            kind = Kinds.EPHEMERAL_GIFT_WRAP,
        )
        assertEquals(Kinds.EPHEMERAL_GIFT_WRAP, wrap.kind)
        assertEquals("4", GiftWrap.unwrap(wrap, TestData.keys(2)).tagValue("seq"))
    }
}

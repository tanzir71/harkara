package io.github.tanzir71.harkara.chat

import android.app.Application
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.chat.ChatCard
import io.github.tanzir71.harkara.core.chat.VoiceClip
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Nip44
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.sms.SmsDm
import io.github.tanzir71.harkara.core.wire.PrefixResolver
import io.github.tanzir71.harkara.core.wire.SmsCodec
import io.github.tanzir71.harkara.lan.FakeLanNetwork
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.sms.SmsComposer
import io.github.tanzir71.harkara.ui.components.Delivery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two real repositories, independent keys/Room stores, and fake SMS/relay/LAN carriers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatSmsTest {
    private val now = 1_795_586_400_000L
    private val phones = ChatTestPhones { now }
    private val relays = mutableListOf<FakeNostrRelay>()

    @After fun close() {
        phones.close()
        relays.forEach { it.shutdown() }
    }

    private suspend fun compact(alice: ChatTestPhones.Phone, bob: ChatTestPhones.Phone, text: String = "Meet by the gate"): Pair<String, Event> {
        val room = alice.chat.startDm(bob.npub)!!
        assertEquals(SendResult.SENT, alice.chat.sendText(room, text))
        val own = alice.lastOwn(room)
        return own.message.id to alice.chat.smsEventFor(own.message.id)!!
    }

    @Test fun `short DM becomes signed encrypted two-part SMS and decrypts into private SMS thread`() = runBlocking<Unit> {
        val alice = phones.phone("Alice")
        val bob = phones.phone("Bob")
        val text = "Meet by the gate"
        val (messageId, event) = compact(alice, bob, text)
        val room = alice.chat.dmRoomId(bob.npub)!!
        assertEquals(Kinds.SMS_DM, event.kind)
        assertTrue(event.validate())
        assertEquals(alice.keys.pubkeyHex, event.pubkey)
        assertEquals(listOf(bob.keys.pubkeyHex), event.tagValues("p"))
        assertEquals(alice.db.chatDao().message(messageId)!!.createdAt, event.createdAt)
        assertFalse(event.toJson().contains(text))
        assertEquals(event, alice.chat.smsEventFor(messageId))
        val recreated = ChatRepository(alice.db, alice.outbox, alice.pool, null, { alice.keys }, { alice.keys })
        assertEquals(event, recreated.smsEventFor(messageId))
        assertFalse(alice.chat.ingestSms(event, Carrier.ONLINE))
        assertNull(alice.db.outboxDao().get(event.id))
        assertEquals(Delivery.Saved, alice.chat.delivery(room, messageId).first())

        val draft = SmsComposer.draft(event) as SmsComposer.Draft.Ready
        assertTrue(draft.parts <= 2)
        val decoded = SmsCodec.decodeText(draft.text, PrefixResolver.of(listOf(alice.keys.pubkeyHex, bob.keys.pubkeyHex)))!!
        assertEquals(event, decoded)
        assertTrue(bob.chat.ingestSms(decoded, Carrier.SMS))
        val incoming = bob.chat.thread(room).first().single()
        assertEquals(text, incoming.message.text)
        assertEquals(Carrier.SMS, incoming.carrier)
        assertFalse(incoming.message.own)
        assertNull(incoming.delivery)
        assertEquals(1, bob.db.chatDao().conversation(room)!!.unread)
        assertNull(bob.chat.smsEventFor(incoming.message.id))
        assertFalse(bob.chat.ingestSms(decoded, Carrier.SMS))
        assertEquals(1, bob.chat.thread(room).first().size)
        assertEquals(1, bob.db.chatDao().conversation(room)!!.unread)

        // Receipt names the compact ID, but updates the original sender bubble, not a second bubble.
        bob.db.chatDao().lanPending(0).forEach { alice.chat.ingest(Event.fromJson(it.json), Carrier.ONLINE) }
        val delivered = alice.awaitDelivery(room, messageId, Delivery.Delivered)
        assertEquals(Carrier.SMS, delivered.carrier)
        bob.chat.markRead(room)
        bob.db.chatDao().lanPending(0).forEach { alice.chat.ingest(Event.fromJson(it.json), Carrier.ONLINE) }
        alice.awaitDelivery(room, messageId, Delivery.Read)
        assertEquals(1, alice.chat.thread(room).first().size)
    }

    @Test fun `wrong recipient wrong ciphertext forged author multiple recipients and blocked author are rejected`() = runBlocking<Unit> {
        val alice = phones.phone("Alice")
        val bob = phones.phone("Bob")
        val outsider = phones.phone("Outsider")
        val (_, event) = compact(alice, bob)
        assertFalse(outsider.chat.ingestSms(event, Carrier.SMS))
        assertFalse(bob.chat.ingestSms(event.copy(pubkey = outsider.keys.pubkeyHex), Carrier.SMS))
        val wrongCiphertext = SmsDm.build(alice.keys, outsider.keys.pubkeyHex, "Not for Bob", now / 1000)
        assertFalse(bob.chat.ingestSms(alice.keys.signEvent(Kinds.SMS_DM, event.tags, wrongCiphertext.content, now / 1000), Carrier.SMS))
        val multi = alice.keys.signEvent(Kinds.SMS_DM, event.tags + listOf(listOf("p", outsider.keys.pubkeyHex)), event.content, now / 1000)
        assertFalse(bob.chat.ingestSms(multi, Carrier.SMS))
        bob.chat.blockAuthor(alice.keys.pubkeyHex)
        assertFalse(bob.chat.ingestSms(event, Carrier.SMS))
        assertTrue(bob.chat.conversations().first().isEmpty())
        assertTrue(outsider.chat.conversations().first().isEmpty())
        alice.chat.blockAuthor(bob.keys.pubkeyHex)
        assertNull(alice.chat.smsEventFor(alice.lastOwn(alice.chat.dmRoomId(bob.npub)!!).message.id))
    }

    @Test fun `groups cards voice public rooms self notes and over-budget text cannot be converted`() = runBlocking<Unit> {
        val alice = phones.phone("Alice")
        val bob = phones.phone("Bob")
        val third = phones.phone("Third")
        val group = alice.chat.createGroup("Neighbours", listOf(bob.npub, third.npub))!!
        alice.chat.sendText(group, "Short group text")
        assertNull(alice.chat.smsEventFor(alice.lastOwn(group).message.id))
        val dm = alice.chat.startDm(bob.npub)!!
        alice.chat.sendCard(dm, ChatCard.CheckIn("Safe", "Gate"))
        assertNull(alice.chat.smsEventFor(alice.lastOwn(dm).message.id))
        alice.chat.sendVoice(dm, VoiceClip(ByteArray(100) { 1 }, "audio/ogg", 500))
        assertNull(alice.chat.smsEventFor(alice.lastOwn(dm).message.id))
        alice.chat.sendText(dm, "x".repeat(33))
        assertNull(alice.chat.smsEventFor(alice.lastOwn(dm).message.id))
        alice.chat.sendText(dm, "আ".repeat(11)) // 33 UTF-8 bytes, not 11 bytes.
        assertNull(alice.chat.smsEventFor(alice.lastOwn(dm).message.id))
        val area = alice.chat.areaChannel(ChatTestPhones.AREA)
        alice.chat.sendText(area, "Short public text")
        assertNull(alice.chat.smsEventFor(alice.lastOwn(area).message.id))
        val self = alice.chat.startDm(alice.npub)!!
        alice.chat.sendText(self, "Note to self")
        assertNull(alice.chat.smsEventFor(alice.lastOwn(self).message.id))
        assertNull(alice.chat.smsEventFor("not-a-message"))
    }

    @Test fun `signed but expired blank and oversized compact plaintext is rejected`() = runBlocking<Unit> {
        val alice = phones.phone("Alice")
        val bob = phones.phone("Bob")
        val (_, event) = compact(alice, bob)
        val key = Nip44.conversationKey(alice.keys.privateKey, bob.keys.pubkeyHex)
        listOf(" ", "x".repeat(33)).forEach { text ->
            val malformed = alice.keys.signEvent(Kinds.SMS_DM, event.tags, Nip44.encrypt(text, key), now / 1000)
            assertFalse(bob.chat.ingestSms(malformed, Carrier.SMS))
        }
        val expired = alice.keys.signEvent(Kinds.SMS_DM, event.tags + listOf(listOf("expiration", (now / 1000 - 1).toString())), event.content, now / 1000)
        assertFalse(bob.chat.ingestSms(expired, Carrier.SMS))
        assertTrue(bob.chat.conversations().first().isEmpty())
    }

    @Test fun `preparing SMS does not mislabel normal gift-wrap delivery as SMS`() = runBlocking<Unit> {
        val alice = phones.phone("Alice")
        val bob = phones.phone("Bob")
        val (original, _) = compact(alice, bob)
        val room = alice.chat.dmRoomId(bob.npub)!!
        val wrap = alice.db.chatDao().wrapsFor(original).single { it.recipient == bob.keys.pubkeyHex && !it.wrapId.startsWith("sms-alias:") }
        bob.chat.ingest(Event.fromJson(wrap.json), Carrier.NODE)
        bob.db.chatDao().lanPending(0).forEach { alice.chat.ingest(Event.fromJson(it.json), Carrier.NODE) }
        val delivered = alice.awaitDelivery(room, original, Delivery.Delivered)
        assertNotEquals(Carrier.SMS, delivered.carrier)
        assertEquals(1, alice.chat.thread(room).first().size)
    }

    @Test fun `SMS and gift-wrap copies deduplicate in either arrival order`() = runBlocking<Unit> {
        val alice = phones.phone("Alice")
        val bob = phones.phone("Bob")
        val (original, event) = compact(alice, bob)
        val room = alice.chat.dmRoomId(bob.npub)!!
        val wrap = alice.db.chatDao().wrapsFor(original).single { it.recipient == bob.keys.pubkeyHex && !it.wrapId.startsWith("sms-alias:") }
        assertTrue(bob.chat.ingestSms(event, Carrier.SMS))
        bob.chat.ingest(Event.fromJson(wrap.json), Carrier.NODE)
        assertEquals(1, bob.chat.thread(room).first().size)
        assertEquals(Carrier.SMS, bob.chat.thread(room).first().single().carrier)

        val otherAlice = phones.phone("Other Alice")
        val otherBob = phones.phone("Other Bob")
        val (second, secondEvent) = compact(otherAlice, otherBob)
        val secondRoom = otherAlice.chat.dmRoomId(otherBob.npub)!!
        val firstWrap = otherAlice.db.chatDao().wrapsFor(second).single { it.recipient == otherBob.keys.pubkeyHex && !it.wrapId.startsWith("sms-alias:") }
        otherBob.chat.ingest(Event.fromJson(firstWrap.json), Carrier.NODE)
        assertFalse(otherBob.chat.ingestSms(secondEvent, Carrier.SMS))
        assertFalse(otherBob.chat.ingestSms(secondEvent, Carrier.SMS))
        assertEquals(1, otherBob.chat.thread(secondRoom).first().size)
        assertEquals(Carrier.NODE, otherBob.chat.thread(secondRoom).first().single().carrier)
    }

    @Test fun `volunteer relay forwarding reaches recipient subscription with ciphertext only`() = runBlocking<Unit> {
        val relay = FakeNostrRelay { now / 1000 }.start().also { relays += it }
        val alice = phones.phone("Alice")
        val bob = phones.phone("Bob", relay.url)
        val volunteer = phones.phone("Volunteer", relay.url)
        bob.awaitRelay()
        volunteer.awaitRelay()
        val (_, event) = compact(alice, bob)
        assertTrue(volunteer.pool.publish(event) > 0)
        val room = alice.chat.dmRoomId(bob.npub)!!
        val received = bob.threadWhen(room) { it.size == 1 }.single()
        assertEquals("Meet by the gate", received.message.text)
        assertEquals(Carrier.ONLINE, received.carrier)
        assertTrue(relay.events.any { it.id == event.id })
        assertTrue(relay.events.none { it.content.contains("Meet by the gate") })
    }

    @Test fun `node LAN delivers compact DM through normal repository ingestion`() = runBlocking<Unit> {
        val wifi = FakeLanNetwork()
        val alice = phones.phone("Alice")
        val bob = phones.phone("Bob", discovery = wifi.discovery())
        val volunteer = phones.phone("Volunteer", discovery = wifi.discovery())
        bob.lan!!.node = true
        volunteer.lan!!.node = true
        bob.awaitLanPeers(1)
        volunteer.awaitLanPeers(1)
        val (_, event) = compact(alice, bob)
        assertTrue(volunteer.lan.publish(event) > 0)
        val room = alice.chat.dmRoomId(bob.npub)!!
        val received = bob.threadWhen(room) { it.size == 1 }.single()
        assertEquals("Meet by the gate", received.message.text)
        assertEquals(Carrier.NODE, received.carrier)
    }
}

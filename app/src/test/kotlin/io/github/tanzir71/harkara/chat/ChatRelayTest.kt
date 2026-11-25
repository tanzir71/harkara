package io.github.tanzir71.harkara.chat

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.chat.ChatCard
import io.github.tanzir71.harkara.core.chat.LocationPrecision
import io.github.tanzir71.harkara.core.chat.LocationShare
import io.github.tanzir71.harkara.core.chat.VoiceClip
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.ui.components.Delivery
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** M5 gate, part 1: DMs, groups, area channel and voice notes between simulated phones over a mock relay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatRelayTest {
    @Volatile private var clock = 1_764_000_000_000L
    private val relay = FakeNostrRelay { clock / 1000 }
    private val phones = ChatTestPhones { clock }

    @Before
    fun setUp() {
        relay.start()
    }

    @After
    fun tearDown() {
        phones.close()
        relay.shutdown()
    }

    private fun tick() {
        clock += 5_000
    }

    @Test
    fun `DMs travel through the relay, decrypt, keep their order and reach Delivered then Read`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi", relay.url)
        val bob = phones.phone("Sumon", relay.url)
        alice.awaitRelay()
        bob.awaitRelay()

        val room = alice.chat.startDm(bob.npub, petName = "Sumon")!!
        val texts = listOf("Pharmacy on Road 8 has insulin, I just checked.", "Great, can someone pick up two pens?", "আমি রোড ৭ এ আছি")
        texts.forEach {
            assertEquals(SendResult.SENT, alice.chat.sendText(room, it))
            tick()
        }
        val first = alice.chat.thread(room).first().first { it.message.text == texts[0] }
        assertTrue(first.message.own && first.delivery != null)

        // Bob gets them decrypted, in order, in the same room, with Alice's name.
        val bobThread = bob.threadWhen(room) { it.size == 3 }
        assertEquals(texts, bobThread.map { it.message.text })
        assertTrue(bobThread.all { !it.message.own && it.senderName == "Rafi" && it.carrier == Carrier.ONLINE })
        // Only gift wraps ever reached the relay: no plaintext.
        assertTrue(relay.events.all { it.kind == Kinds.GIFT_WRAP })
        assertTrue(relay.events.none { e -> texts.any { e.content.contains(it) } })

        // Bob's delivered receipts come back, then reading the thread turns them to Read.
        val delivered = alice.awaitDelivery(room, first.message.id, Delivery.Delivered)
        assertEquals(Carrier.ONLINE, delivered.carrier)
        bob.chat.markRead(room)
        alice.awaitDelivery(room, first.message.id, Delivery.Read)
        texts.drop(1).forEach { t ->
            val id = alice.chat.thread(room).first().first { it.message.text == t }.message.id
            alice.awaitDelivery(room, id, Delivery.Read)
        }

        // Bob answers; Alice sees one thread in order, and the unread count clears on read.
        tick()
        assertEquals(SendResult.SENT, bob.chat.sendText(room, "Yes, I can go in 10 min"))
        val aliceThread = alice.threadWhen(room) { it.size == 4 }
        assertEquals(texts + "Yes, I can go in 10 min", aliceThread.map { it.message.text })
        assertEquals("Sumon", aliceThread.last().senderName)
        withTimeout(10_000) { alice.chat.conversations().first { list -> list.single { it.conversation.id == room }.conversation.unread == 1 } }
        alice.chat.markRead(room)
        assertEquals(0, alice.chat.conversations().first().single { it.conversation.id == room }.conversation.unread)
        bob.awaitDelivery(room, aliceThread.last().message.id, Delivery.Read)
    }

    @Test
    fun `group messages reach every member and need everyone's receipt`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi", relay.url)
        val bob = phones.phone("Sumon", relay.url)
        val carol = phones.phone("Ma", relay.url)
        listOf(alice, bob, carol).forEach { it.awaitRelay() }

        val room = alice.chat.createGroup("Road 7 building", listOf(bob.npub, carol.npub))!!
        assertEquals(SendResult.SENT, alice.chat.sendText(room, "Who has power on Road 7?"))
        val sent = alice.lastOwn(room)

        for (member in listOf(bob, carol)) {
            val thread = member.threadWhen(room) { it.isNotEmpty() }
            assertEquals("Who has power on Road 7?", thread.single().message.text)
            val conversation = member.chat.conversations().first().single { it.conversation.id == room }.conversation
            assertEquals("Road 7 building", conversation.subject)
            assertEquals(3, conversation.memberList().size)
            assertEquals(ConversationType.GROUP.name, conversation.type)
        }
        alice.awaitDelivery(room, sent.message.id, Delivery.Delivered)

        // One reader is not enough for Read.
        bob.chat.markRead(room)
        withTimeout(10_000) {
            while (alice.db.chatDao().receipt(sent.message.id, bob.keys.pubkeyHex)?.status != Delivery.Read.name) delay(50)
        }
        assertEquals(Delivery.Delivered, alice.chat.thread(room).first().single { it.message.id == sent.message.id }.delivery)
        carol.chat.markRead(room)
        alice.awaitDelivery(room, sent.message.id, Delivery.Read)

        // Replies fan out to everyone, cards included, in order.
        tick()
        val card = LocationShare.card(23.7465, 90.376, LocationPrecision.APPROX, "On my way", "Road 8 pharmacy")
        assertEquals(SendResult.SENT, bob.chat.sendCard(room, card))
        tick()
        assertEquals(SendResult.SENT, carol.chat.sendText(room, "I'm safe, home now"))
        for (member in listOf(alice, bob, carol)) {
            val thread = member.threadWhen(room) { it.size == 3 }
            assertEquals(listOf("Who has power on Road 7?", card.summary(), "I'm safe, home now"), thread.map { it.message.text })
            assertEquals(card, thread[1].card)
        }
        assertEquals("Sumon", alice.chat.thread(room).first()[1].senderName)
    }

    @Test
    fun `area channel messages are signed by the area key and reach the area`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi", relay.url)
        val bob = phones.phone("Sumon", relay.url)
        alice.awaitRelay()
        bob.awaitRelay()
        val channel = alice.chat.areaChannel(ChatTestPhones.AREA)
        assertEquals(channel, bob.chat.areaChannel(ChatTestPhones.AREA))

        assertEquals(SendResult.PHONE_NUMBER, alice.chat.sendText(channel, "call me 01711 234567"))
        assertEquals(SendResult.SENT, alice.chat.sendText(channel, "Water back on Road 27"))
        val own = alice.lastOwn(channel)
        alice.awaitDelivery(channel, own.message.id, Delivery.ReachedNetwork)

        val received = bob.threadWhen(channel) { it.isNotEmpty() }.single()
        assertEquals("Water back on Road 27", received.message.text)
        assertEquals("Rafi", received.senderName)
        val event = relay.events.single { it.kind == Kinds.AREA_CHAT }
        assertEquals(ChatTestPhones.AREA, event.tagValue("g"))
        assertTrue(event.pubkey != alice.keys.pubkeyHex)
        assertNull(received.delivery)
    }

    @Test
    fun `a voice note is split under 16 KB per event and put back together`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi", relay.url)
        val bob = phones.phone("Sumon", relay.url)
        alice.awaitRelay()
        bob.awaitRelay()
        val room = alice.chat.startDm(bob.npub)!!
        val recorder = FakeVoiceRecorder(VoiceClip(Random(5).nextBytes(30_000), "audio/ogg", 12_400))
        assertTrue(recorder.start())
        val clip = recorder.stop()!!

        assertEquals(SendResult.SENT, alice.chat.sendVoice(room, clip))
        val note = bob.threadWhen(room) { it.isNotEmpty() }.single()
        assertEquals(MessageType.VOICE.name, note.message.type)
        assertEquals(12_400L, note.message.voiceDurationMs)
        assertArrayEquals(clip.bytes, bob.chat.voiceClip(note.message.id)!!.bytes)
        alice.awaitDelivery(room, alice.lastOwn(room).message.id, Delivery.Delivered)

        assertTrue(relay.events.size >= 16)
        relay.events.forEach { e: Event -> assertTrue("event is ${e.jsonSize()} bytes", e.jsonSize() < Event.MAX_JSON_BYTES) }
        assertEquals(SendResult.TOO_LONG, alice.chat.sendVoice(room, VoiceClip(ByteArray(100), "audio/ogg", 20_000)))
    }

    @Test
    fun `offline messages wait as Saved and go when a relay connects`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi")
        val bob = phones.phone("Sumon", relay.url)
        bob.awaitRelay()
        val room = alice.chat.startDm(bob.npub)!!
        alice.chat.sendText(room, "Sent with no signal")
        val own = alice.lastOwn(room)
        assertEquals(Delivery.Saved, own.delivery)
        assertEquals(Carrier.SAVED, own.carrier)

        alice.pool.setRelays(listOf(relay.url))
        assertEquals("Sent with no signal", bob.threadWhen(room) { it.isNotEmpty() }.single().message.text)
        alice.awaitDelivery(room, own.message.id, Delivery.Delivered)
    }

    @Test
    fun `cards in chats render from tags`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi", relay.url)
        val bob = phones.phone("Sumon", relay.url)
        alice.awaitRelay()
        bob.awaitRelay()
        val room = alice.chat.startDm(bob.npub)!!
        val cards = listOf(
            ChatCard.Task("Task · Pickup", "৳120", "Two insulin pens", 1, "Accepted 21:16", "Sumon · 38 done"),
            ChatCard.CheckIn("I'm safe", "Home · 21:40"),
            ChatCard.Iou("৳500", true, "Lunch at Star Kabab", "Waiting for Sumon to sign", "Today", false),
            ChatCard.Pin("Water point", "Tubewell behind the lake mosque", "Public", 4, 1, "stock"),
        )
        cards.forEach {
            alice.chat.sendCard(room, it)
            tick()
        }
        assertEquals(cards, bob.threadWhen(room) { it.size == 4 }.map { it.card })
    }
}

/** Hands out a fixed clip, like a microphone that always records the same thing. */
class FakeVoiceRecorder(private val clip: VoiceClip?) : VoiceRecorder {
    override var isRecording = false
        private set

    override fun start(): Boolean {
        isRecording = true
        return true
    }

    override fun stop(): VoiceClip? {
        val recorded = if (isRecording) clip else null
        isRecording = false
        return recorded
    }

    override fun cancel() {
        isRecording = false
    }
}

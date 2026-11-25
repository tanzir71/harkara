package io.github.tanzir71.harkara.chat

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.chat.VoiceClip
import io.github.tanzir71.harkara.lan.FakeLanNetwork
import io.github.tanzir71.harkara.relay.LiveConnectivityMonitor
import io.github.tanzir71.harkara.ui.components.Delivery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * M5 gate, part 2: the same chats through "everyone on this Wi-Fi" with no internet relay at all — real
 * room servers on localhost, OkHttp pools between them, and a fake NSD network for discovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatLanTest {
    @Volatile private var clock = 1_764_000_000_000L
    private val phones = ChatTestPhones { clock }
    private val wifi = FakeLanNetwork()

    @After
    fun tearDown() = phones.close()

    private fun tick() {
        clock += 5_000
    }

    @Test
    fun `DMs and receipts cross the Wi-Fi room with no internet`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi", discovery = wifi.discovery())
        val bob = phones.phone("Sumon", discovery = wifi.discovery())
        alice.awaitLanPeers(1)
        bob.awaitLanPeers(1)
        assertEquals(0, alice.pool.connectedCount)

        val room = alice.chat.startDm(bob.npub)!!
        assertEquals(SendResult.SENT, alice.chat.sendText(room, "Power is out on Road 7, are you ok?"))
        tick()
        assertEquals(SendResult.SENT, alice.chat.sendText(room, "Message me here, it works on the hall Wi-Fi"))
        val sent = alice.chat.thread(room).first()

        val received = bob.threadWhen(room) { it.size == 2 }
        assertEquals(sent.map { it.message.text }, received.map { it.message.text })
        assertTrue(received.all { it.carrier == Carrier.NODE && it.senderName == "Rafi" })

        val delivered = alice.awaitDelivery(room, sent[0].message.id, Delivery.Delivered)
        assertEquals(Carrier.NODE, delivered.carrier)
        bob.chat.markRead(room)
        alice.awaitDelivery(room, sent[1].message.id, Delivery.Read)

        // Delivered over the room, so the relay copy no longer waits in the outbox.
        val bobWrap = alice.db.chatDao().wrapsFor(sent[0].message.id).single { it.recipient == bob.keys.pubkeyHex }
        assertEquals(Delivery.Delivered.name, alice.db.outboxDao().get(bobWrap.wrapId)!!.state)

        // And back the other way, voice note included.
        tick()
        val clip = VoiceClip(Random(9).nextBytes(20_000), "audio/ogg", 8_000)
        assertEquals(SendResult.SENT, bob.chat.sendVoice(room, clip))
        val note = alice.threadWhen(room) { it.size == 3 }.last()
        assertEquals(MessageType.VOICE.name, note.message.type)
        assertArrayEquals(clip.bytes, alice.chat.voiceClip(note.message.id)!!.bytes)
        bob.awaitDelivery(room, note.message.id, Delivery.Delivered)
    }

    @Test
    fun `a three-person group works on the Wi-Fi room alone`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi", discovery = wifi.discovery())
        val bob = phones.phone("Sumon", discovery = wifi.discovery())
        val carol = phones.phone("Ma", discovery = wifi.discovery())
        listOf(alice, bob, carol).forEach { it.awaitLanPeers(2) }

        val room = alice.chat.createGroup("Road 7 building", listOf(bob.npub, carol.npub))!!
        assertEquals(SendResult.SENT, alice.chat.sendText(room, "Pharmacy on Road 8 has insulin, I just checked."))
        val first = alice.lastOwn(room)
        for (member in listOf(bob, carol)) {
            assertEquals("Pharmacy on Road 8 has insulin, I just checked.", member.threadWhen(room) { it.isNotEmpty() }.single().message.text)
        }
        alice.awaitDelivery(room, first.message.id, Delivery.Delivered)

        tick()
        assertEquals(SendResult.SENT, carol.chat.sendText(room, "I'm safe, home now"))
        tick()
        assertEquals(SendResult.SENT, bob.chat.sendText(room, "Great, can someone pick up two pens?"))
        val expected = listOf("Pharmacy on Road 8 has insulin, I just checked.", "I'm safe, home now", "Great, can someone pick up two pens?")
        for (member in listOf(alice, bob, carol)) {
            assertEquals(expected, member.threadWhen(room) { it.size == 3 }.map { it.message.text })
            member.chat.markRead(room)
        }
        val read = alice.awaitDelivery(room, first.message.id, Delivery.Read)
        assertEquals(Carrier.NODE, read.carrier)
        val bobsLast = bob.lastOwn(room)
        bob.awaitDelivery(room, bobsLast.message.id, Delivery.Read)
    }

    @Test
    fun `the Wi-Fi room's public messages reach everyone in the room and the pill says local Wi-Fi`() = runBlocking<Unit> {
        val alice = phones.phone("Rafi", discovery = wifi.discovery())
        val bob = phones.phone("Sumon", discovery = wifi.discovery())
        alice.awaitLanPeers(1)
        val monitor = LiveConnectivityMonitor(alice.pool, alice.outbox, phones.scope, { 3 }, alice.lan!!.peerCount)
        assertEquals(Carrier.NODE, withTimeout(10_000) { monitor.state.first { it.best == Carrier.NODE } }.best)

        val roomId = alice.chat.wifiRoom()
        assertEquals(SendResult.SENT, alice.chat.sendText(roomId, "Generator is on in the hall, phones can charge"))
        val own = alice.lastOwn(roomId)
        val heard = bob.threadWhen(roomId) { it.isNotEmpty() }.single()
        assertEquals("Generator is on in the hall, phones can charge", heard.message.text)
        assertEquals("Rafi", heard.senderName)
        assertEquals(Carrier.NODE, heard.carrier)
        val reached = alice.awaitDelivery(roomId, own.message.id, Delivery.ReachedNetwork)
        assertEquals(Carrier.NODE, reached.carrier)
        // The room never goes to internet relays.
        assertEquals(null, alice.db.outboxDao().get(own.message.id))

        // A phone joining later still gets DMs that waited in the room.
        tick()
        val dmRoom = alice.chat.startDm(bob.npub)!!
        bob.lan!!.stop()
        alice.chat.sendText(dmRoom, "Are you still in the hall?")
        bob.lan.start()
        assertEquals("Are you still in the hall?", bob.threadWhen(dmRoom) { it.isNotEmpty() }.single().message.text)
    }
}

package io.github.tanzir71.harkara.ui.chat

import io.github.tanzir71.harkara.core.mesh.FakeTransportHub
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.wire.MeshFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val alice = KeyPair(ByteArray(32) { 1 })
    private val bob = KeyPair(ByteArray(32) { 2 })

    @Test
    fun `signed area messages cross the fake transport and verify`() = runTest {
        val hub = FakeTransportHub()
        val aliceViewModel = ChatViewModel(alice, hub.createTransport(), "wh0r0", TestScope(testScheduler))
        val bobViewModel = ChatViewModel(bob, hub.createTransport(), "wh0r0", TestScope(testScheduler))
        advanceUntilIdle()

        aliceViewModel.sendBroadcast("anyone nearby?")
        advanceUntilIdle()

        val message = bobViewModel.messages.value.single()
        assertEquals("anyone nearby?", message.body)
        assertTrue(message.sender.startsWith("npub1"))
        assertEquals(1, hub.wire.size)
    }

    @Test
    fun `forged messages are dropped`() = runTest {
        val hub = FakeTransportHub()
        val mallory = hub.createTransport()
        val bobViewModel = ChatViewModel(bob, hub.createTransport(), "wh0r0", TestScope(testScheduler))
        advanceUntilIdle()
        val real = alice.signEvent(Kinds.AREA_CHAT, listOf(listOf("g", "wh0r0")), "meet at the gate")

        mallory.send(MeshFrame.event(real.copy(content = "meet at the river")))
        advanceUntilIdle()

        assertEquals(emptyList<ChatMessageUi>(), bobViewModel.messages.value)
    }
}

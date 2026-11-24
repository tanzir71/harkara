package io.github.tanzir71.harkara.ui.chat

import io.github.tanzir71.harkara.core.CryptoBox
import io.github.tanzir71.harkara.core.FakeTransportHub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Test
    fun `chat viewmodels exchange broadcast through fake transport`() = runTest {
        val hub = FakeTransportHub()
        val alice = CryptoBox.generateIdentity(random(1))
        val bob = CryptoBox.generateIdentity(random(2))
        val aliceViewModel = ChatViewModel(alice, hub.createTransport(), TestScope(testScheduler))
        val bobViewModel = ChatViewModel(bob, hub.createTransport(), TestScope(testScheduler))

        aliceViewModel.sendBroadcast("anyone nearby?")
        advanceUntilIdle()

        assertEquals(listOf("anyone nearby?"), bobViewModel.messages.value.map { it.body })
    }

    private fun random(seed: Int): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(seed.toByte())) }
}

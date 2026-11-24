package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.wire.MeshFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeTransportHubTest {
    @Test
    fun `event frames cross the hub as bytes and arrive verifiable`() = runTest {
        val hub = FakeTransportHub()
        val a = hub.createTransport()
        val b = hub.createTransport()
        val event = TestData.post(TestData.keys(1))
        var got: MeshFrame? = null
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { got = b.incoming.first() }

        a.send(MeshFrame.event(event))
        job.join()

        assertEquals(event, got!!.event())
        assertTrue(got!!.event().verify())
        assertEquals(1, hub.wire.size)
    }
}

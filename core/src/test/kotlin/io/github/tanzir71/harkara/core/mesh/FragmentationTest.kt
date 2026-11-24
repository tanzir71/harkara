package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.wire.FrameType
import io.github.tanzir71.harkara.core.wire.MeshFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class FragmentationTest {
    private val start = Instant.parse("2026-11-25T06:00:00Z")
    private val bigEvent = TestData.post(TestData.keys(1), text = (1..80).joinToString(" ") { "r${it * 31 % 97}" })

    @Test
    fun `fragments fit the mtu and reassemble out of order into a verifiable event`() {
        val frame = MeshFrame.event(bigEvent).forRelay()
        val fragments = Fragmenter.split(frame, mtu = 120)
        val reassembler = FragmentReassembler()

        assertTrue(fragments.size > 2)
        assertTrue(fragments.all { it.type == FrameType.FRAGMENT && it.encode().size <= 120 })
        val shuffled = fragments.reversed()
        shuffled.dropLast(1).forEach { assertNull(reassembler.accept(it, start)) }
        val whole = reassembler.accept(shuffled.last(), start.plusSeconds(5))!!

        assertEquals(frame, whole)
        assertEquals(1, whole.hops)
        assertEquals(bigEvent, whole.event())
        assertTrue(whole.event().verify())
        assertEquals(0, reassembler.pendingSets)
    }

    @Test
    fun `small frames are not fragmented`() {
        val frame = MeshFrame.digest(emptyList())
        assertEquals(listOf(frame), Fragmenter.split(frame, mtu = 120))
    }

    @Test
    fun `incomplete sets are dropped after sixty seconds`() {
        val fragments = Fragmenter.split(MeshFrame.event(bigEvent), mtu = 120)
        val reassembler = FragmentReassembler()

        fragments.dropLast(1).forEach { reassembler.accept(it, start) }
        assertEquals(1, reassembler.pendingSets)
        // The last piece arrives 61 s later: the set has been dropped, so nothing completes.
        assertNull(reassembler.accept(fragments.last(), start.plusSeconds(61)))
        assertEquals(1, reassembler.pendingSets, "only the late piece is held now")
        assertNull(reassembler.accept(fragments.first(), start.plusSeconds(200)))
    }

    @Test
    fun `within sixty seconds the set still completes`() {
        val fragments = Fragmenter.split(MeshFrame.event(bigEvent), mtu = 120)
        val reassembler = FragmentReassembler()
        fragments.dropLast(1).forEach { reassembler.accept(it, start) }
        assertEquals(bigEvent, reassembler.accept(fragments.last(), start.plusSeconds(59))!!.event())
    }

    @Test
    fun `corrupted chunks never produce a frame`() {
        val fragments = Fragmenter.split(MeshFrame.event(bigEvent), mtu = 120)
        val reassembler = FragmentReassembler()
        val payload = fragments[1].payload
        payload[payload.size - 1] = (payload[payload.size - 1] + 1).toByte()
        val corrupted = MeshFrame(FrameType.FRAGMENT, 1, 0, 0, payload)

        val results = (listOf(fragments[0], corrupted) + fragments.drop(2)).map { reassembler.accept(it, start) }

        assertTrue(results.all { it == null })
    }

    @Test
    fun `pending sets are bounded`() {
        val reassembler = FragmentReassembler(maxPending = 4)
        (1..10).forEach { n ->
            val event = TestData.post(TestData.keys(n), text = (1..80).joinToString(" ") { "x$n-$it" })
            reassembler.accept(Fragmenter.split(MeshFrame.event(event), 120).first(), start)
        }
        assertEquals(4, reassembler.pendingSets)
    }
}

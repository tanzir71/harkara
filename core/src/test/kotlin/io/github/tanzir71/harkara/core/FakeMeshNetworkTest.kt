package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

class FakeMeshNetworkTest {
    @Test
    fun `three hop relay delivers broadcast across a line topology`() {
        val network = FakeMeshNetwork()
        val nodes = (0 until 4).map { network.addNode(id(it)) }
        network.connect(nodes[0], nodes[1])
        network.connect(nodes[1], nodes[2])
        network.connect(nodes[2], nodes[3])

        nodes[0].broadcast(packet(99, nodes[0].id))
        network.drain()

        assertEquals(listOf(99L), nodes[3].received.map { it.msgId })
    }

    @Test
    fun `dedupe storm in dense topology stores one copy per node`() {
        val network = FakeMeshNetwork()
        val nodes = (0 until 6).map { network.addNode(id(it)) }
        nodes.indices.forEach { a ->
            (a + 1 until nodes.size).forEach { b -> network.connect(nodes[a], nodes[b]) }
        }

        nodes[0].broadcast(packet(123, nodes[0].id))
        network.drain()

        nodes.drop(1).forEach { node ->
            assertEquals(1, node.received.count { it.msgId == 123L }, "node ${node.id}")
        }
    }

    @Test
    fun `random twenty node topology delivers broadcast to at least ninety five percent`() {
        val random = Random(8)
        val network = FakeMeshNetwork()
        val nodes = (0 until 20).map { network.addNode(id(it)) }
        (0 until nodes.lastIndex).forEach { network.connect(nodes[it], nodes[it + 1]) }
        repeat(30) {
            val a = random.nextInt(nodes.size)
            val b = random.nextInt(nodes.size)
            if (a != b) network.connect(nodes[a], nodes[b])
        }

        nodes[0].broadcast(packet(987, nodes[0].id))
        network.drain()

        val delivered = nodes.drop(1).count { node -> node.received.any { it.msgId == 987L } }
        assertTrue(delivered.toDouble() / (nodes.size - 1) >= 0.95, "delivered=$delivered")
    }

    private fun id(index: Int): NearId = NearId(ByteArray(16) { index.toByte() })

    private fun packet(msgId: Long, sender: NearId): MeshPacket =
        MeshPacket.chat(msgId, sender, "hello".encodeToByteArray(), Instant.ofEpochMilli(5_000))
}

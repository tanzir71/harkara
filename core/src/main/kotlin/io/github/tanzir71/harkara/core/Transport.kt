package io.github.tanzir71.harkara.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Packet transport adapter contract implemented by BLE, internet, and fakes. */
interface MeshTransport {
    /** Incoming packets from connected peers. */
    val incoming: Flow<MeshPacket>

    /** Sends one packet to connected peers. */
    suspend fun send(packet: MeshPacket)
}

/** In-memory transport hub for app and protocol tests. */
class FakeTransportHub {
    private val transports = mutableSetOf<FakeTransport>()

    /** Creates a transport endpoint attached to this hub. */
    fun createTransport(): MeshTransport =
        FakeTransport(this).also { transports += it }

    private suspend fun publish(sender: FakeTransport, packet: MeshPacket) {
        transports.filterNot { it == sender }.forEach { it.deliver(packet) }
    }

    private class FakeTransport(private val hub: FakeTransportHub) : MeshTransport {
        private val packets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)

        override val incoming: Flow<MeshPacket> = packets.asSharedFlow()

        override suspend fun send(packet: MeshPacket) {
            hub.publish(this, packet)
        }

        suspend fun deliver(packet: MeshPacket) {
            packets.emit(packet)
        }
    }
}

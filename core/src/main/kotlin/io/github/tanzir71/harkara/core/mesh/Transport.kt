package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.wire.MeshFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A link that moves mesh frames to and from nearby phones (BLE, Wi-Fi Aware, LAN, or a fake). */
interface MeshTransport {
    /** Frames arriving from connected peers. */
    val incoming: Flow<MeshFrame>

    /** Sends one frame to connected peers. */
    suspend fun send(frame: MeshFrame)
}

/**
 * In-memory hub for app and protocol tests. Every frame goes through its byte encoding on the way, so
 * tests exercise the real wire format.
 */
class FakeTransportHub {
    private val transports = mutableListOf<FakeTransport>()

    /** Frames that went through the hub, encoded. */
    val wire: MutableList<ByteArray> = mutableListOf()

    fun createTransport(): MeshTransport = FakeTransport(this).also { transports += it }

    private suspend fun publish(sender: FakeTransport, frame: MeshFrame) {
        val bytes = frame.encode()
        wire += bytes
        transports.filter { it !== sender }.forEach { it.deliver(MeshFrame.decode(bytes)) }
    }

    private class FakeTransport(private val hub: FakeTransportHub) : MeshTransport {
        private val frames = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)

        override val incoming: Flow<MeshFrame> = frames.asSharedFlow()

        override suspend fun send(frame: MeshFrame) {
            hub.publish(this, frame)
        }

        suspend fun deliver(frame: MeshFrame) {
            frames.emit(frame)
        }
    }
}

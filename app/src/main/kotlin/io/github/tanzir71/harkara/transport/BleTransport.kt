package io.github.tanzir71.harkara.transport

import io.github.tanzir71.harkara.core.MeshPacket
import io.github.tanzir71.harkara.core.MeshTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Android BLE mesh transport adapter shell. */
class BleTransport : MeshTransport {
    override val incoming: Flow<MeshPacket> = emptyFlow()

    override suspend fun send(packet: MeshPacket) = Unit
}

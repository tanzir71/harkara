package io.github.tanzir71.harkara.transport

import io.github.tanzir71.harkara.core.MeshPacket
import io.github.tanzir71.harkara.core.MeshTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Opportunistic internet sync transport adapter shell. */
class InternetTransport : MeshTransport {
    override val incoming: Flow<MeshPacket> = emptyFlow()

    override suspend fun send(packet: MeshPacket) = Unit
}

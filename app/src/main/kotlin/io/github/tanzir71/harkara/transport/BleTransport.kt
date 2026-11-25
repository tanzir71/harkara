package io.github.tanzir71.harkara.transport

import io.github.tanzir71.harkara.core.mesh.MeshTransport
import io.github.tanzir71.harkara.core.wire.MeshFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Android BLE mesh transport adapter shell (M6). */
class BleTransport : MeshTransport {
    override val incoming: Flow<MeshFrame> = emptyFlow()

    override suspend fun send(frame: MeshFrame) = Unit
}

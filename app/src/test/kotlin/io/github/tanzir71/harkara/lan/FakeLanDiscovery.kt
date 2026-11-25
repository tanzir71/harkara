package io.github.tanzir71.harkara.lan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** One simulated Wi-Fi: every [FakeLanDiscovery] on it sees every other advertised room server. */
class FakeLanNetwork(private val host: String = "127.0.0.1") {
    private val members = CopyOnWriteArrayList<FakeLanDiscovery>()
    private val advertised = linkedMapOf<String, LanPeer>()

    fun discovery(): FakeLanDiscovery = FakeLanDiscovery(this).also { members += it }

    @Synchronized
    internal fun advertise(name: String, port: Int) {
        advertised[name] = LanPeer(name, host, port)
        refresh()
    }

    @Synchronized
    internal fun withdraw(name: String) {
        advertised.remove(name)
        refresh()
    }

    private fun refresh() = members.forEach { it.update(advertised.values.toList()) }
}

/** [LanDiscovery] without NSD: advertising and finding happen on a [FakeLanNetwork]. */
class FakeLanDiscovery internal constructor(private val network: FakeLanNetwork) : LanDiscovery {
    private val mutablePeers = MutableStateFlow<List<LanPeer>>(emptyList())
    private var own: String? = null
    override val peers: StateFlow<List<LanPeer>> = mutablePeers.asStateFlow()

    override fun start(serviceName: String, port: Int) {
        own = serviceName
        network.advertise(serviceName, port)
    }

    override fun stop() {
        own?.let(network::withdraw)
        own = null
        mutablePeers.value = emptyList()
    }

    internal fun update(advertised: List<LanPeer>) {
        mutablePeers.value = if (own == null) emptyList() else advertised.filter { it.name != own }
    }
}

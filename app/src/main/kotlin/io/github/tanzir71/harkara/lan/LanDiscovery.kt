package io.github.tanzir71.harkara.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap

/** Another phone's Wi-Fi room server. */
data class LanPeer(val name: String, val host: String, val port: Int) {
    val url: String get() = if (host.contains(':')) "ws://[$host]:$port" else "ws://$host:$port"
}

/** Finds other Harkara phones on the same Wi-Fi and advertises ours. Android NSD on phones, a fake in tests. */
interface LanDiscovery {
    /** Peers seen right now (never ourselves). */
    val peers: StateFlow<List<LanPeer>>

    /** Advertises our room server as [serviceName] on [port] and starts looking for others. */
    fun start(serviceName: String, port: Int)

    fun stop()

    companion object {
        /** mDNS / DNS-SD service type of the room. */
        const val SERVICE_TYPE = "_harkara._tcp"
    }
}

/**
 * NSD (mDNS/DNS-SD) discovery. Holds a Wi-Fi multicast lock only while running, which is only while the
 * app is in the foreground (battery rule). Unverified on hardware; see docs/HARDWARE.md.
 */
class NsdLanDiscovery(context: Context) : LanDiscovery {
    private val app = context.applicationContext

    // Looked up only when the room starts (in the foreground), never when the app container is built.
    private val nsd: NsdManager? by lazy { app.getSystemService(NsdManager::class.java) }
    private val wifi: WifiManager? by lazy { app.getSystemService(WifiManager::class.java) }
    private val found = ConcurrentHashMap<String, LanPeer>()
    private val mutablePeers = MutableStateFlow<List<LanPeer>>(emptyList())
    override val peers: StateFlow<List<LanPeer>> = mutablePeers.asStateFlow()

    private var lock: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var ownName: String? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    override fun start(serviceName: String, port: Int) {
        val manager = nsd ?: return
        stop()
        ownName = serviceName
        lock = runCatching { wifi?.createMulticastLock("harkara-lan")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = LanDiscovery.SERVICE_TYPE
            this.port = port
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                ownName = info.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        val disc = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName != ownName && info.serviceType.trimEnd('.') == LanDiscovery.SERVICE_TYPE) resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                publish()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        registration = reg
        discovery = disc
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }
        runCatching { manager.discoverServices(LanDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc) }
    }

    @Synchronized
    override fun stop() {
        val manager = nsd ?: return
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        runCatching { lock?.release() }
        lock = null
        resolveQueue.clear()
        resolving = false
        found.clear()
        publish()
    }

    /** Before API 34 NSD resolves one service at a time, so resolves are queued. */
    @Synchronized
    private fun resolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        if (!resolving) next()
    }

    @Synchronized
    private fun next() {
        val manager = nsd ?: return
        val info = resolveQueue.removeFirstOrNull()
        if (info == null) {
            resolving = false
            return
        }
        resolving = true
        @Suppress("DEPRECATION")
        val started = runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val address = resolved.host
                        if (address != null && resolved.serviceName != ownName) {
                            val host = if (address is Inet6Address) address.hostAddress.orEmpty().substringBefore('%') else address.hostAddress.orEmpty()
                            if (host.isNotEmpty()) found[resolved.serviceName] = LanPeer(resolved.serviceName, host, resolved.port)
                            publish()
                        }
                        next()
                    }

                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        next()
                    }
                },
            )
        }.isSuccess
        if (!started) next()
    }

    private fun publish() {
        mutablePeers.value = found.values.sortedBy { it.name }
    }
}

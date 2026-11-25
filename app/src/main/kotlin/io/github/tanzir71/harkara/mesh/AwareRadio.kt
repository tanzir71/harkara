package io.github.tanzir71.harkara.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import io.github.tanzir71.harkara.core.mesh.MeshRadio
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi Aware (NAN) as a second mesh radio on phones that have it: publish + subscribe to the Harkara service
 * and move frames as discovery messages (small, so the engine fragments to [MESSAGE_BYTES]). Peers that go
 * quiet for [PEER_TIMEOUT_MS] are dropped. Unverified on hardware; most budget phones have no Wi-Fi Aware.
 */
@SuppressLint("MissingPermission")
class AwareRadio(private val context: Context) : MeshRadio {
    override val kind: String = "aware"

    private val manager: WifiAwareManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) context.getSystemService(WifiAwareManager::class.java) else null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var listener: MeshRadio.Listener? = null
    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private val peers = ConcurrentHashMap<String, PeerHandle>()
    private val lastHeard = ConcurrentHashMap<String, Long>()
    private val messageIds = AtomicInteger()

    val available: Boolean get() = manager?.isAvailable == true

    override fun start(token: ByteArray, listener: MeshRadio.Listener) {
        val manager = manager?.takeIf { it.isAvailable } ?: return
        this.listener = listener
        runCatching {
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    this@AwareRadio.session = session
                    session.publish(PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(token).build(), callback, handler)
                    session.subscribe(SubscribeConfig.Builder().setServiceName(SERVICE).build(), callback, handler)
                }
            }, handler)
        }
        handler.postDelayed(sweeper, PEER_TIMEOUT_MS)
    }

    override fun stop() {
        handler.removeCallbacks(sweeper)
        runCatching { publish?.close() }
        runCatching { subscribe?.close() }
        runCatching { session?.close() }
        publish = null
        subscribe = null
        session = null
        val l = listener
        listener = null
        peers.keys.toList().forEach { l?.onPeerDown(it) }
        peers.clear()
        lastHeard.clear()
    }

    /** Aware discovery runs in the radio firmware with its own duty cycle; nothing to switch. */
    override fun setScanning(on: Boolean) = Unit

    override fun send(peer: String, bytes: ByteArray, priority: Int): Boolean {
        val handle = peers[peer] ?: return false
        val s = subscribe ?: publish ?: return false
        return runCatching { s.sendMessage(handle, messageIds.incrementAndGet(), bytes) }.isSuccess
    }

    private fun heard(handle: PeerHandle): String {
        val id = "peer-${handle.hashCode()}"
        lastHeard[id] = System.currentTimeMillis()
        if (peers.putIfAbsent(id, handle) == null) listener?.onPeerUp(id, MESSAGE_BYTES)
        return id
    }

    private val callback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(session: PublishDiscoverySession) {
            publish = session
        }

        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            subscribe = session
        }

        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
            heard(peerHandle)
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            val id = heard(peerHandle)
            listener?.onBytes(id, message)
        }
    }

    private val sweeper = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            lastHeard.filterValues { now - it > PEER_TIMEOUT_MS }.keys.forEach { id ->
                lastHeard.remove(id)
                if (peers.remove(id) != null) listener?.onPeerDown(id)
            }
            if (listener != null) handler.postDelayed(this, PEER_TIMEOUT_MS)
        }
    }

    companion object {
        const val SERVICE = "harkara-mesh"

        /** Discovery messages carry at least 255 bytes on every Aware phone. */
        const val MESSAGE_BYTES = 255
        const val PEER_TIMEOUT_MS = 120_000L
    }
}

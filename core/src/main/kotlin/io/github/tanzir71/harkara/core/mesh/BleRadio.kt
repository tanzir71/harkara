package io.github.tanzir71.harkara.core.mesh

import java.util.PriorityQueue

/**
 * The GATT operations [BleRadio] needs. On phones this is Android's BluetoothLeAdvertiser, BluetoothLeScanner,
 * BluetoothGatt and BluetoothGattServer (one Harkara service with one characteristic: centrals write to it,
 * peripherals notify on it); in tests it is a fake. Every call is asynchronous; results come back as
 * [GattEvents].
 */
interface GattLayer {
    /** Opens the GATT server and wires [events]; false when Bluetooth LE is off, missing or not permitted. */
    fun open(events: GattEvents): Boolean

    fun close()

    /** Advertises the Harkara service with [token] as service data (scan response). */
    fun advertise(token: ByteArray)

    fun scan(on: Boolean)

    // Central role: we dialled a peer's GATT server.
    fun connect(address: String)

    fun disconnect(address: String)

    fun requestMtu(address: String, mtu: Int)

    /** Finds the Harkara characteristic and turns on its notifications. */
    fun subscribe(address: String)

    /** Writes [bytes] to the peer's characteristic (we are its central). */
    fun write(address: String, bytes: ByteArray): Boolean

    // Peripheral role: a peer dialled our server.

    /** Notifies the subscribed central at [address]. */
    fun notify(address: String, bytes: ByteArray): Boolean
}

/** Callbacks from a [GattLayer]; they may come on any thread. */
interface GattEvents {
    fun onScanResult(address: String, token: ByteArray?, rssi: Int)

    fun onConnected(address: String)

    fun onMtuChanged(address: String, mtu: Int)

    /** Service found and notifications on: the link we dialled is ready. */
    fun onSubscribed(address: String)

    fun onNotification(address: String, bytes: ByteArray)

    fun onWriteDone(address: String)

    fun onDisconnected(address: String)

    fun onCentralMtu(address: String, mtu: Int)

    /** A central turned on our notifications: the link it dialled is ready. */
    fun onCentralSubscribed(address: String)

    fun onCentralWrite(address: String, bytes: ByteArray)

    fun onNotificationSent(address: String)

    fun onCentralDisconnected(address: String)
}

/**
 * Bluetooth LE dual-role mesh radio (after bitchat-android's design): every phone advertises and scans, and of
 * two phones that see each other only the one with the smaller token dials ([LinkPolicy]), so each pair
 * shares one link. A link is up once notifications are on; each side then writes one frame at a time in
 * priority order (SOS first) and waits for the stack's write/notify callback before the next. Listener calls
 * are made outside this radio's lock.
 */
class BleRadio(
    private val gatt: GattLayer,
    private val maxLinks: () -> Int = { MeshProfile.SAVER.maxLinks },
    private val clock: () -> Long = System::currentTimeMillis,
) : MeshRadio, GattEvents {
    override val kind: String = "ble"

    @Volatile private var listener: MeshRadio.Listener? = null
    private var token = ByteArray(0)
    private val links = HashMap<String, Link>()
    private val dialing = HashMap<String, Long>()
    private val dialledMtu = HashMap<String, Int>()
    private val centralMtu = HashMap<String, Int>()
    private var seq = 0L

    @Volatile
    var running: Boolean = false
        private set

    val linkCount: Int @Synchronized get() = links.size

    override fun start(token: ByteArray, listener: MeshRadio.Listener) {
        synchronized(this) {
            if (running) return
            this.token = token.copyOf()
            this.listener = listener
        }
        if (!gatt.open(this)) return
        running = true
        gatt.advertise(token)
    }

    override fun stop() {
        val gone = synchronized(this) {
            if (!running) return
            running = false
            links.keys.toList().also {
                links.clear()
                dialing.clear()
                dialledMtu.clear()
                centralMtu.clear()
            }
        }
        gatt.scan(false)
        gatt.close()
        val l = listener
        listener = null
        gone.forEach { l?.onPeerDown(it) }
    }

    override fun setScanning(on: Boolean) {
        if (running) gatt.scan(on)
    }

    override fun send(peer: String, bytes: ByteArray, priority: Int): Boolean {
        val link = synchronized(this) { links[peer]?.also { it.queue += Out(priority, seq++, bytes.copyOf()) } } ?: return false
        pump(link)
        return true
    }

    private fun pump(link: Link) {
        while (true) {
            val next = synchronized(this) {
                if (links[link.address] !== link) return
                if (link.inFlightSince != 0L && clock() - link.inFlightSince < WRITE_TIMEOUT_MS) return
                link.inFlightSince = 0L
                link.queue.poll()?.also { link.inFlightSince = clock() }
            } ?: return
            val ok = if (link.dialled) gatt.write(link.address, next.bytes) else gatt.notify(link.address, next.bytes)
            if (ok) return
            synchronized(this) { link.inFlightSince = 0L }
        }
    }

    private fun writeDone(address: String) {
        val link = synchronized(this) { links[address]?.also { it.inFlightSince = 0L } } ?: return
        pump(link)
    }

    override fun onScanResult(address: String, token: ByteArray?, rssi: Int) {
        listener?.onSighting(address, rssi)
        val dial = synchronized(this) {
            if (!running) return
            val now = clock()
            dialing.entries.removeAll { now - it.value > DIAL_TIMEOUT_MS }
            LinkPolicy.shouldDial(this.token, token, links.size + dialing.size, maxLinks(), address in links || address in dialing)
                .also { if (it) dialing[address] = now }
        }
        if (dial) gatt.connect(address)
    }

    override fun onConnected(address: String) {
        if (synchronized(this) { address in dialing }) gatt.requestMtu(address, REQUESTED_MTU)
    }

    override fun onMtuChanged(address: String, mtu: Int) {
        if (synchronized(this) { address !in dialing.keys.also { dialledMtu[address] = mtu } }) return
        gatt.subscribe(address)
    }

    override fun onSubscribed(address: String) {
        val mtu = synchronized(this) {
            if (dialing.remove(address) == null || address in links) return
            val payload = (dialledMtu.remove(address) ?: DEFAULT_ATT_MTU) - ATT_OVERHEAD
            links[address] = Link(address, dialled = true)
            payload
        }
        listener?.onPeerUp(address, mtu)
    }

    override fun onNotification(address: String, bytes: ByteArray) {
        if (synchronized(this) { links[address]?.dialled == true }) listener?.onBytes(address, bytes)
    }

    override fun onWriteDone(address: String) = writeDone(address)

    override fun onDisconnected(address: String) {
        val wasUp = synchronized(this) {
            dialing.remove(address)
            dialledMtu.remove(address)
            if (links[address]?.dialled == true) links.remove(address) != null else false
        }
        if (wasUp) listener?.onPeerDown(address)
    }

    override fun onCentralMtu(address: String, mtu: Int) {
        synchronized(this) { centralMtu[address] = mtu }
    }

    override fun onCentralSubscribed(address: String) {
        val mtu = synchronized(this) {
            if (!running || address in links) return
            links[address] = Link(address, dialled = false)
            (centralMtu[address] ?: DEFAULT_ATT_MTU) - ATT_OVERHEAD
        }
        listener?.onPeerUp(address, mtu)
    }

    override fun onCentralWrite(address: String, bytes: ByteArray) {
        if (synchronized(this) { links[address]?.dialled == false }) listener?.onBytes(address, bytes)
    }

    override fun onNotificationSent(address: String) = writeDone(address)

    override fun onCentralDisconnected(address: String) {
        val wasUp = synchronized(this) {
            centralMtu.remove(address)
            if (links[address]?.dialled == false) links.remove(address) != null else false
        }
        if (wasUp) listener?.onPeerDown(address)
    }

    private class Out(val priority: Int, val seq: Long, val bytes: ByteArray)

    private class Link(val address: String, val dialled: Boolean) {
        val queue = PriorityQueue<Out>(compareByDescending<Out> { it.priority }.thenBy { it.seq })
        var inFlightSince = 0L
    }

    companion object {
        /** Asked for on every link; Android answers with what both sides support (up to 517). */
        const val REQUESTED_MTU = 517
        const val DEFAULT_ATT_MTU = 23
        const val ATT_OVERHEAD = 3
        const val WRITE_TIMEOUT_MS = 5_000L
        const val DIAL_TIMEOUT_MS = 30_000L
    }
}

package io.github.tanzir71.harkara.core.mesh

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pretend radio space for tests: [MeshRadio]s whose links the test draws by hand ([link], [unlink]). Bytes
 * are delivered in order on one background thread, the way real radio callbacks arrive, and every write is
 * checked against the link MTU. Nothing here touches hardware.
 */
class FakeRadioAir(private val mtu: Int = 185) {
    private val radios = LinkedHashMap<String, FakeRadio>()
    private val links = mutableSetOf<Pair<String, String>>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-radio-air").apply { isDaemon = true } }
    private val pending = AtomicInteger()
    private val bytes = AtomicLong()

    /** Encoded bytes written over all links. */
    val bytesOnAir: Long get() = bytes.get()

    /** Priorities of every write, in order (to check SOS goes first). */
    val priorities: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf())

    fun radio(name: String): MeshRadio = synchronized(this) { radios.getOrPut(name) { FakeRadio(name) } }

    /** Links two radios (both must be started for the link to come up). */
    fun link(a: String, b: String) {
        val up = synchronized(this) {
            val key = key(a, b)
            if (!links.add(key)) return
            val ra = radios.getValue(a)
            val rb = radios.getValue(b)
            if (ra.listener != null && rb.listener != null) ra to rb else null
        }
        up?.let { (ra, rb) ->
            // Complete both sides' link callbacks before delivering bytes queued by either side.
            post { ra.listener?.onPeerUp(rb.name, mtu); rb.listener?.onPeerUp(ra.name, mtu) }
        }
    }

    fun unlink(a: String, b: String) {
        synchronized(this) { if (!links.remove(key(a, b))) return }
        val ra = radios.getValue(a)
        val rb = radios.getValue(b)
        post { ra.listener?.onPeerDown(rb.name) }
        post { rb.listener?.onPeerDown(ra.name) }
    }

    /** Reports [rssi] for [seen] in [observer]'s scans. */
    fun sight(observer: String, seen: String, rssi: Int) {
        val r = radios.getValue(observer)
        post { r.listener?.onSighting(seen, rssi) }
    }

    /** Waits until every queued delivery (and anything it caused) has run. */
    fun awaitIdle(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (pending.get() > 0) {
            check(System.currentTimeMillis() < deadline) { "fake radio air did not go quiet" }
            Thread.sleep(5)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun key(a: String, b: String) = if (a < b) a to b else b to a

    private fun post(block: () -> Unit) {
        pending.incrementAndGet()
        executor.execute {
            try {
                block()
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun linkedTo(name: String): List<FakeRadio> = synchronized(this) {
        links.mapNotNull { (a, b) -> if (a == name) radios[b] else if (b == name) radios[a] else null }
    }

    private inner class FakeRadio(val name: String) : MeshRadio {
        @Volatile var listener: MeshRadio.Listener? = null
        @Volatile var scanOn = false

        override val kind: String = "fake"

        override fun start(token: ByteArray, listener: MeshRadio.Listener) {
            this.listener = listener
            linkedTo(name).filter { it.listener != null }.forEach { other ->
                post { listener.onPeerUp(other.name, mtu); other.listener?.onPeerUp(name, mtu) }
            }
        }

        override fun stop() {
            val others = linkedTo(name).filter { it.listener != null }
            listener = null
            others.forEach { other -> post { other.listener?.onPeerDown(name) } }
        }

        override fun setScanning(on: Boolean) {
            scanOn = on
        }

        override fun send(peer: String, bytes: ByteArray, priority: Int): Boolean {
            check(bytes.size <= mtu) { "write of ${bytes.size} B over a $mtu B link" }
            val target = synchronized(this@FakeRadioAir) { radios[peer]?.takeIf { key(name, peer) in links } } ?: return false
            this@FakeRadioAir.bytes.addAndGet(bytes.size.toLong())
            priorities += priority
            val copy = bytes.copyOf()
            post { target.listener?.onBytes(name, copy) }
            return true
        }
    }
}

package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.store.EventStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The BLE dual-role radio against a fake GATT layer: who dials, link setup, MTU, pacing and priority. */
class BleRadioTest {
    private val air = FakeGattAir()

    @AfterEach
    fun tearDown() = air.shutdown()

    private class Recorder : MeshRadio.Listener {
        val up = CopyOnWriteArrayList<Pair<String, Int>>()
        val down = CopyOnWriteArrayList<String>()
        val bytes = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val sightings = CopyOnWriteArrayList<String>()

        override fun onPeerUp(peer: String, mtu: Int) {
            up += peer to mtu
        }

        override fun onPeerDown(peer: String) {
            down += peer
        }

        override fun onBytes(peer: String, bytes: ByteArray) {
            this.bytes += peer to bytes
        }

        override fun onSighting(peer: String, rssi: Int) {
            sightings += peer
        }
    }

    @Test
    fun `two phones that see each other end up with exactly one link`() {
        val a = BleRadio(air.gatt("AA"))
        val b = BleRadio(air.gatt("BB"))
        val ra = Recorder()
        val rb = Recorder()
        air.inRange("AA", "BB")
        a.start(byteArrayOf(1, 0, 0, 0), ra)
        b.start(byteArrayOf(2, 0, 0, 0), rb)
        a.setScanning(true)
        b.setScanning(true)
        air.awaitIdle()

        assertEquals(listOf("BB" to 514), ra.up.toList())
        assertEquals(listOf("AA" to 514), rb.up.toList())
        assertEquals(1, air.connections, "only the smaller token dials")
        assertTrue("BB" in ra.sightings)

        assertTrue(a.send("BB", byteArrayOf(1, 2, 3), 1))
        assertTrue(b.send("AA", byteArrayOf(4, 5), 1))
        air.awaitIdle()
        assertEquals(listOf(4, 5), ra.bytes.single().second.map { it.toInt() })
        assertEquals(listOf(1, 2, 3), rb.bytes.single().second.map { it.toInt() })

        b.stop()
        air.awaitIdle()
        assertEquals(listOf("BB"), ra.down.toList())
    }

    @Test
    fun `writes go one at a time, highest priority first`() {
        val a = BleRadio(air.gatt("AA"))
        val b = BleRadio(air.gatt("BB"))
        val rb = Recorder()
        air.inRange("AA", "BB")
        a.start(byteArrayOf(1), Recorder())
        b.start(byteArrayOf(2), rb)
        a.setScanning(true)
        air.awaitIdle()

        air.holdWrites = true
        a.send("BB", byteArrayOf(1), MeshPriority.POST)
        a.send("BB", byteArrayOf(2), MeshPriority.POST)
        a.send("BB", byteArrayOf(3), MeshPriority.SYNC)
        a.send("BB", byteArrayOf(5), MeshPriority.SOS)
        air.awaitIdle()
        assertEquals(1, air.writesInFlight, "the stack takes one write at a time")
        air.holdWrites = false
        air.releaseWrites()
        air.awaitIdle()
        assertEquals(listOf(1, 5, 2, 3), rb.bytes.map { it.second.single().toInt() })
    }

    @Test
    fun `a phone dials no more links than its mode allows`() {
        val hub = BleRadio(air.gatt("00"), maxLinks = { 2 })
        hub.start(byteArrayOf(0), Recorder())
        val others = (1..5).map { i ->
            val name = "0$i"
            air.inRange("00", name)
            BleRadio(air.gatt(name)).also { it.start(byteArrayOf(i.toByte()), Recorder()) }
        }
        hub.setScanning(true)
        air.awaitIdle()
        assertEquals(2, hub.linkCount)
        assertEquals(2, others.count { it.linkCount == 1 })
    }

    @Test
    fun `engines over BLE carry an event two hops along a line`() {
        val now = Instant.ofEpochSecond(TestData.NOW)
        val received = Collections.synchronizedList(mutableListOf<Event>())
        fun engine(name: String, id: Int, listener: MeshEngine.Listener = object : MeshEngine.Listener {}) =
            MeshEngine(EventStore(setOf("wh0r")), { ByteArray(16) { id.toByte() } }, { now }, MeshProfile.EVENT, listener)
                .also { it.attach(BleRadio(air.gatt(name), maxLinks = { 7 })) }
        air.inRange("A", "B")
        air.inRange("B", "C")
        val a = engine("A", 1)
        val b = engine("B", 2)
        val c = engine("C", 3, object : MeshEngine.Listener {
            override fun onEvent(event: Event, hops: Int, peer: String) {
                received += event
            }
        })
        // Scan windows repeat on every tick; the second round finds the phones that started later.
        listOf(a, b, c).forEach { it.tick() }
        air.awaitIdle()
        val post = TestData.post(TestData.keys(9))
        assertTrue(a.originate(post))
        air.awaitIdle()
        assertEquals(listOf(post.id), received.map { it.id })
    }

    /** Pretend GATT for a few phones: advertising, scanning, dialling, MTU exchange, subscribe, write/notify. */
    private class FakeGattAir {
        private val executor = Executors.newSingleThreadExecutor()
        private val pending = AtomicInteger()
        private val phones = HashMap<String, FakeGatt>()
        private val range = mutableSetOf<Set<String>>()
        private val held = mutableListOf<() -> Unit>()

        @Volatile var connections = 0
        @Volatile var holdWrites = false
        val writesInFlight: Int get() = synchronized(held) { held.size }

        fun gatt(address: String): GattLayer = FakeGatt(address).also { phones[address] = it }

        fun inRange(a: String, b: String) {
            range += setOf(a, b)
        }

        fun releaseWrites() {
            val list = synchronized(held) { held.toList().also { held.clear() } }
            list.forEach { post(it) }
        }

        fun awaitIdle() {
            val deadline = System.currentTimeMillis() + 10_000
            while (pending.get() > 0) {
                check(System.currentTimeMillis() < deadline) { "fake GATT did not go quiet" }
                Thread.sleep(5)
            }
        }

        fun shutdown() {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }

        fun post(block: () -> Unit) {
            pending.incrementAndGet()
            executor.execute {
                try {
                    block()
                } finally {
                    pending.decrementAndGet()
                }
            }
        }

        inner class FakeGatt(val address: String) : GattLayer {
            var events: GattEvents? = null
            var token: ByteArray? = null
            val mtuWith = HashMap<String, Int>()
            val linked = mutableSetOf<String>()

            override fun open(events: GattEvents): Boolean {
                this.events = events
                return true
            }

            override fun close() {
                val peers = linked.toList()
                linked.clear()
                token = null
                peers.forEach { p ->
                    val other = phones.getValue(p)
                    other.linked -= address
                    post { other.events?.onDisconnected(address) }
                    post { other.events?.onCentralDisconnected(address) }
                }
                events = null
            }

            override fun advertise(token: ByteArray) {
                this.token = token
            }

            override fun scan(on: Boolean) {
                if (!on) return
                phones.values.filter { it !== this && setOf(address, it.address) in range && it.token != null }.forEach { other ->
                    val t = other.token
                    post { events?.onScanResult(other.address, t, -60) }
                }
            }

            override fun connect(address: String) {
                connections++
                post { events?.onConnected(address) }
            }

            override fun disconnect(address: String) = Unit

            override fun requestMtu(address: String, mtu: Int) {
                val agreed = minOf(mtu, 517)
                mtuWith[address] = agreed
                val other = phones.getValue(address)
                post { other.events?.onCentralMtu(this.address, agreed) }
                post { events?.onMtuChanged(address, agreed) }
            }

            override fun subscribe(address: String) {
                val other = phones.getValue(address)
                linked += address
                other.linked += this.address
                post { other.events?.onCentralSubscribed(this.address) }
                post { events?.onSubscribed(address) }
            }

            override fun write(address: String, bytes: ByteArray): Boolean {
                check(bytes.size <= (mtuWith[address] ?: 23) - 3) { "write over the MTU" }
                val other = phones.getValue(address)
                val copy = bytes.copyOf()
                post { other.events?.onCentralWrite(this.address, copy) }
                done { events?.onWriteDone(address) }
                return true
            }

            override fun notify(address: String, bytes: ByteArray): Boolean {
                val other = phones.getValue(address)
                val copy = bytes.copyOf()
                post { other.events?.onNotification(this.address, copy) }
                done { events?.onNotificationSent(address) }
                return true
            }

            private fun done(callback: () -> Unit) {
                if (holdWrites) synchronized(held) { held += callback } else post(callback)
            }
        }
    }
}

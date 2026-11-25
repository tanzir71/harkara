package io.github.tanzir71.harkara.khata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.khata.IouStatus
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.khata.Settlement
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gate for M8 (relay and QR paths; the LAN path lives with the LAN room tests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KhataFlowTest {
    private val relay = FakeNostrRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phones = mutableListOf<Phone>()

    inner class Phone(val name: String) {
        val keys = KeyPair.generate()
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val pool = RelayPool(scope)
        val outbox = Outbox(db, pool)
        val khata = KhataRepository(db, outbox, { listOf(pool) }, { keys }, { name })

        init {
            outbox.start(scope)
            khata.startIngest(scope, pool)
        }

        val me get() = Party(name, keys.pubkeyHex)

        suspend fun online() {
            pool.setRelays(listOf(relay.url))
            withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }
            khata.subscribe()
        }

        suspend fun await(id: String, status: IouStatus) =
            withTimeout(15_000) { khata.ious().first { list -> list.any { it.terms.id == id && it.status == status } } }.first { it.terms.id == id }
    }

    private fun phone(name: String) = Phone(name).also { phones += it }

    @Before
    fun setUp() {
        relay.start()
    }

    @After
    fun tearDown() {
        phones.forEach { it.pool.stop(); it.db.close() }
        scope.cancel()
        relay.shutdown()
    }

    @Test
    fun `co-sign and settle over the relay, end-to-end encrypted`() = runBlocking<Unit> {
        val tania = phone("Tania")
        val rafi = phone("Rafi Hasan")
        tania.online(); rafi.online()

        val proposed = tania.khata.create(IouDirection.THEY_OWE_ME, rafi.me, 500, "Star Kabab lunch")
        assertEquals(IouStatus.CLAIMED, proposed.status)

        val atRafi = rafi.await(proposed.terms.id, IouStatus.CLAIMED)
        assertTrue(atRafi.waitingFor(rafi.keys.pubkeyHex))
        assertEquals("Star Kabab lunch", atRafi.terms.note)
        rafi.khata.sign(atRafi)

        val agreed = tania.await(proposed.terms.id, IouStatus.AGREED)
        assertEquals(setOf(tania.keys.pubkeyHex, rafi.keys.pubkeyHex), agreed.signedBy)

        // The relay only ever saw gift wraps: no IOU terms in the clear.
        assertTrue(relay.events.all { it.kind == 1059 })
        assertTrue(relay.events.none { it.content.contains("Star Kabab") })

        rafi.khata.recordSettlement(rafi.await(proposed.terms.id, IouStatus.AGREED), Settlement("bkash", 500, "9F4K2L7Q"))
        val settling = tania.await(proposed.terms.id, IouStatus.SETTLING)
        assertEquals("9F4K2L7Q", settling.settlement?.trxId)
        tania.khata.confirmSettlement(settling)
        rafi.await(proposed.terms.id, IouStatus.SETTLED)
    }

    @Test
    fun `QR handshake co-signs face to face with no network at all`() = runBlocking<Unit> {
        val tania = phone("Tania")
        val rafi = phone("Rafi")
        val proposed = tania.khata.create(IouDirection.I_OWE_THEM, rafi.me, 1850, "Mess bill, November")
        assertEquals(0, tania.pool.connectedCount)

        val scanned = rafi.khata.importQr(tania.khata.qrFor(proposed))!!
        assertTrue(scanned.waitingFor(rafi.keys.pubkeyHex))
        val signed = rafi.khata.sign(scanned)
        val back = tania.khata.importQr(rafi.khata.qrFor(signed))!!
        assertEquals(IouStatus.AGREED, back.status)
        assertEquals(IouStatus.AGREED, rafi.khata.get(proposed.terms.id)!!.status)
        assertNull(phone("Nobody").khata.importQr(tania.khata.qrFor(back)))
    }

    @Test
    fun `split bill makes one exact IOU per member`() = runBlocking<Unit> {
        val tania = phone("Tania")
        val shares = tania.khata.splitBill(listOf(Party("Rafi"), Party("Nabila"), Party("Arnob")), 1000, "CNG share")
        assertEquals(listOf(334L, 333L, 333L), shares.map { it.terms.amount })
        assertTrue(shares.all { it.status == IouStatus.CLAIMED && it.terms.creditor.pubkey == tania.keys.pubkeyHex })
        assertEquals(3, tania.khata.ious().first().size)
    }
}

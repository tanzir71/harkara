package io.github.tanzir71.harkara.khata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.khata.IouStatus
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.khata.Settlement
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.lan.FakeLanNetwork
import io.github.tanzir71.harkara.lan.LanRoom
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

/** Gate for M8, LAN path: two phones on the same Wi-Fi co-sign and settle an IOU with no internet relay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KhataLanTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifi = FakeLanNetwork()
    private val rooms = mutableListOf<LanRoom>()

    inner class Phone(name: String) {
        val keys = KeyPair.generate()
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val pool = RelayPool(scope)
        val outbox = Outbox(db, pool).also { it.start(scope) }
        val lan = LanRoom(scope, wifi.discovery(), InetAddress.getLoopbackAddress()).also { rooms += it; it.start() }
        val khata = KhataRepository(db, outbox, { listOf(pool) }, { keys }, { name }, lan = lan).also { it.startIngest(scope, pool) }
        val me get() = Party("x", keys.pubkeyHex)

        suspend fun ready() {
            withTimeout(10_000) { lan.pool.status.first { s -> s.values.count { it == RelayStatus.CONNECTED } >= 1 } }
            khata.subscribe()
        }

        suspend fun await(id: String, status: IouStatus) =
            withTimeout(15_000) { khata.ious().first { l -> l.any { it.terms.id == id && it.status == status } } }.first { it.terms.id == id }
    }

    @After
    fun tearDown() {
        rooms.forEach { it.stop() }
        scope.cancel()
    }

    @Test
    fun `co-sign and settle over the Wi-Fi room alone`() = runBlocking<Unit> {
        val tania = Phone("Tania")
        val rafi = Phone("Rafi")
        tania.ready(); rafi.ready()
        assertEquals(0, tania.pool.connectedCount)

        val iou = tania.khata.create(IouDirection.THEY_OWE_ME, rafi.me, 450, "Generator fuel")
        val atRafi = rafi.await(iou.terms.id, IouStatus.CLAIMED)
        rafi.khata.sign(atRafi)
        tania.await(iou.terms.id, IouStatus.AGREED)

        rafi.khata.recordSettlement(rafi.await(iou.terms.id, IouStatus.AGREED), Settlement("cash", 450))
        tania.khata.confirmSettlement(tania.await(iou.terms.id, IouStatus.SETTLING))
        rafi.await(iou.terms.id, IouStatus.SETTLED)
    }
}

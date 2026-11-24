package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration

class ConnectivityStateTest {
    @Test
    fun `nothing wired means saved`() {
        assertEquals(Carrier.SAVED, ConnectivityState.Saved.best)
    }

    @Test
    fun `pill names the highest rung that reaches people`() {
        assertEquals(Carrier.ONLINE, ConnectivityState(available = Carrier.entries.toSet()).best)
        assertEquals(Carrier.MESH, ConnectivityState(available = setOf(Carrier.MESH, Carrier.SMS, Carrier.QR, Carrier.SAVED)).best)
        assertEquals(Carrier.SMS, ConnectivityState(available = setOf(Carrier.SMS, Carrier.QR, Carrier.SAVED)).best)
    }

    @Test
    fun `qr alone does not count as reaching people`() {
        assertEquals(Carrier.SAVED, ConnectivityState(available = setOf(Carrier.QR)).best)
    }

    @Test
    fun `category expiry matches loka`() {
        assertEquals(Duration.ofHours(3), Category.TRAFFIC.expiry)
        assertEquals(Duration.ofHours(4), Category.CROWD.expiry)
        assertEquals(Duration.ofHours(12), Category.ALERT.expiry)
        assertEquals(Duration.ofHours(24), Category.STOCK.expiry)
        assertEquals(Duration.ofHours(48), Category.CHATTER.expiry)
        assertNull(Category.FOOD.expiry)
        assertEquals(Category.CROWD, Category.fromTag("busy"))
        assertEquals(Category.CHATTER, Category.fromTag("general"))
    }
}

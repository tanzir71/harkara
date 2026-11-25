package io.github.tanzir71.harkara.chat

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.data.ChatReceiptEntity
import io.github.tanzir71.harkara.data.WrapState
import io.github.tanzir71.harkara.ui.chats.ChatPresenter
import io.github.tanzir71.harkara.ui.components.Delivery
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryRulesTest {
    private val me = "me"
    private val members = listOf("me", "bob", "carol")

    private fun wrap(to: String, relay: Delivery? = null, relayCarrier: Carrier = Carrier.ONLINE, lan: Delivery = Delivery.Saved) =
        WrapState("w-$to", "m", to, lan.name, relay?.name, relayCarrier.name)

    private fun receipt(from: String, status: Delivery) = ChatReceiptEntity("m", "c", from, status.name, 0)

    @Test
    fun `transport states need every recipient, the self copy does not count`() {
        val saved = listOf(wrap("me", Delivery.Saved, Carrier.SAVED), wrap("bob", Delivery.Saved, Carrier.SAVED), wrap("carol", Delivery.Saved, Carrier.SAVED))
        assertEquals(DeliveryRules.Result(Delivery.Saved, Carrier.SAVED), DeliveryRules.compute(me, members, saved, emptyList()))

        val left = listOf(wrap("me", Delivery.ReachedNetwork), wrap("bob", Delivery.LeftPhone), wrap("carol", Delivery.Saved, Carrier.SAVED))
        assertEquals(Delivery.LeftPhone, DeliveryRules.compute(me, members, left, emptyList()).delivery)

        val reached = listOf(wrap("bob", Delivery.ReachedNetwork), wrap("carol", lan = Delivery.ReachedNetwork, relay = Delivery.Saved, relayCarrier = Carrier.SAVED))
        assertEquals(Delivery.ReachedNetwork, DeliveryRules.compute(me, members, reached, emptyList()).delivery)
    }

    @Test
    fun `receipts from every other member make Delivered and Read`() {
        val wraps = listOf(wrap("bob", Delivery.ReachedNetwork), wrap("carol", Delivery.ReachedNetwork))
        assertEquals(Delivery.ReachedNetwork, DeliveryRules.compute(me, members, wraps, listOf(receipt("bob", Delivery.Read))).delivery)
        assertEquals(Delivery.Delivered, DeliveryRules.compute(me, members, wraps, listOf(receipt("bob", Delivery.Read), receipt("carol", Delivery.Delivered))).delivery)
        assertEquals(Delivery.Read, DeliveryRules.compute(me, members, wraps, listOf(receipt("bob", Delivery.Read), receipt("carol", Delivery.Read))).delivery)
        // A receipt from someone outside the room changes nothing.
        assertEquals(Delivery.ReachedNetwork, DeliveryRules.compute(me, members, wraps, listOf(receipt("mallory", Delivery.Read))).delivery)
    }

    @Test
    fun `the carrier is the route that got furthest, and an outbox row that never left is not the relay`() {
        val lan = listOf(wrap("bob", Delivery.Delivered, Carrier.SAVED, lan = Delivery.ReachedNetwork))
        assertEquals(Carrier.NODE, DeliveryRules.compute(me, listOf("me", "bob"), lan, listOf(receipt("bob", Delivery.Delivered))).carrier)
        val relay = listOf(wrap("bob", Delivery.ReachedNetwork, lan = Delivery.LeftPhone))
        assertEquals(Carrier.ONLINE, DeliveryRules.compute(me, listOf("me", "bob"), relay, emptyList()).carrier)
        val publicRoom = listOf(wrap("*", lan = Delivery.ReachedNetwork))
        assertEquals(DeliveryRules.Result(Delivery.ReachedNetwork, Carrier.NODE), DeliveryRules.compute(me, emptyList(), publicRoom, emptyList()))
    }

    @Test
    fun `initials and durations`() {
        assertEquals("R7", ChatPresenter.initials("Road 7 building"))
        assertEquals("Su", ChatPresenter.initials("Sumon"))
        assertEquals("a3", ChatPresenter.initials("npub1a3f9…x0q2"))
        assertEquals("0:12", ChatPresenter.duration(12))
        assertEquals("1:05", ChatPresenter.duration(65))
    }
}

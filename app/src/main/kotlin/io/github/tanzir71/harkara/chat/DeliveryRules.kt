package io.github.tanzir71.harkara.chat

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.data.ChatReceiptEntity
import io.github.tanzir71.harkara.data.WrapState
import io.github.tanzir71.harkara.ui.components.Delivery

/**
 * Delivery state of one of our messages, from its wraps and the members' receipts:
 *
 * - Saved: no wrap has left the phone yet.
 * - Left this phone: a wrap was written to a relay socket or a Wi-Fi room peer.
 * - Reached the network: every recipient's wrap got an `OK` from a relay or a room server.
 * - Delivered: every other member sent a delivered (or read) receipt.
 * - Read: every other member sent a read receipt.
 *
 * The carrier is the rung that moved it: relays (Online) or the Wi-Fi room (Node), whichever got further.
 */
object DeliveryRules {
    data class Result(val delivery: Delivery, val carrier: Carrier)

    fun compute(me: String, members: List<String>, wraps: List<WrapState>, receipts: List<ChatReceiptEntity>): Result {
        val recipientWraps = wraps.filter { it.recipient != me }.ifEmpty { wraps }
        var relayBest = Delivery.Saved
        var lanBest = Delivery.Saved
        var transport = if (recipientWraps.isEmpty()) Delivery.Saved else Delivery.ReachedNetwork
        var anyLeft = false
        for (w in recipientWraps) {
            val relay = relayOf(w)
            val lan = (runCatching { Delivery.valueOf(w.lanState) }.getOrDefault(Delivery.Saved)).cap()
            if (relay > relayBest) relayBest = relay
            if (lan > lanBest) lanBest = lan
            val best = maxOf(relay, lan)
            if (best >= Delivery.LeftPhone) anyLeft = true
            if (best < Delivery.ReachedNetwork) transport = Delivery.Saved
        }
        if (transport < Delivery.ReachedNetwork && anyLeft) transport = Delivery.LeftPhone

        val others = members.filter { it != me }
        val byMember = receipts.groupBy { it.member }.mapValues { (_, list) -> list.maxOf { status(it) } }
        val receiptState = when {
            others.isEmpty() -> Delivery.Saved
            others.all { byMember[it] == Delivery.Read } -> Delivery.Read
            others.all { (byMember[it] ?: Delivery.Saved) >= Delivery.Delivered } -> Delivery.Delivered
            else -> Delivery.Saved
        }
        val delivery = maxOf(transport, receiptState)
        val carrier = when {
            relayBest < Delivery.LeftPhone && lanBest < Delivery.LeftPhone -> if (receiptState >= Delivery.Delivered) Carrier.ONLINE else Carrier.SAVED
            lanBest > relayBest -> Carrier.NODE
            else -> Carrier.ONLINE
        }
        return Result(delivery, carrier)
    }

    /** The relay side of a wrap: an outbox row that never left by relay counts as Saved. */
    private fun relayOf(w: WrapState): Delivery {
        val state = w.relayState?.let { runCatching { Delivery.valueOf(it) }.getOrNull() } ?: return Delivery.Saved
        if (w.relayCarrier == Carrier.SAVED.name) return Delivery.Saved
        return state.cap()
    }

    private fun Delivery.cap(): Delivery = if (this > Delivery.ReachedNetwork) Delivery.ReachedNetwork else this

    private fun status(r: ChatReceiptEntity): Delivery = runCatching { Delivery.valueOf(r.status) }.getOrDefault(Delivery.Delivered)
}

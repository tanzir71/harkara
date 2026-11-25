package io.github.tanzir71.harkara.core

/**
 * The rungs of the connectivity ladder, best first. Every event moves over one of these,
 * and the connectivity pill names the highest rung that can reach people right now.
 */
enum class Carrier {
    /** Internet relays. */
    ONLINE,

    /** A nearby phone with data lifts events onto the internet. */
    BRIDGE,

    /** Neighbourhood node on the local Wi-Fi (LAN, no internet). */
    NODE,

    /** Bluetooth mesh / Wi-Fi Aware with nearby phones. */
    MESH,

    /** Text message (prefilled SMS app by default). */
    SMS,

    /** Shown on screen and scanned, hand to hand. */
    QR,

    /** Nothing reachable: saved on the phone, sends later. */
    SAVED,
}

/** Snapshot of what can carry events right now. */
data class ConnectivityState(
    /** Rungs that are currently able to move an event. [Carrier.QR] and [Carrier.SAVED] are always available. */
    val available: Set<Carrier> = setOf(Carrier.QR, Carrier.SAVED),
    /** Phones in mesh reach, when the mesh is on. */
    val meshPeers: Int = 0,
    /** Relays answering, out of those configured. */
    val relaysReachable: Int = 0,
    val relaysConfigured: Int = 0,
    /** Events waiting on the phone for a route. */
    val waiting: Int = 0,
    /** Whether the Bluetooth mesh is running (Mesh mode or Disaster mode). */
    val meshOn: Boolean = false,
    /** How far events from this phone travel over the mesh (the mode's TTL). */
    val meshMaxHops: Int = 0,
    /** Hops to the nearest phone with data over the mesh; null when none is in reach. */
    val bridgeHops: Int? = null,
    /** Phones in the Wi-Fi room with us, and how many of them run a neighbourhood node. */
    val lanPeers: Int = 0,
    val nodePeers: Int = 0,
) {
    /**
     * The rung the pill shows: the best rung that reaches *people*. QR needs someone standing next
     * to you, so with nothing else open the honest answer is [Carrier.SAVED].
     */
    val best: Carrier
        get() = Carrier.entries.firstOrNull { it in available && it != Carrier.QR } ?: Carrier.SAVED

    companion object {
        /** Nothing wired yet: everything waits on the phone. */
        val Saved = ConnectivityState()
    }
}

/**
 * The connectivity ladder applied to one event: which carriers move it on their own right now, best first.
 * SMS and QR need the person (an SMS app, a camera), so they are never automatic; with nothing listed the
 * event stays Saved until a rung opens.
 */
object CarrierLadder {
    fun automatic(state: ConnectivityState, meshEligible: Boolean, nodeEligible: Boolean = true): List<Carrier> = buildList {
        if (state.relaysReachable > 0) add(Carrier.ONLINE)
        if (state.nodePeers > 0 && nodeEligible) add(Carrier.NODE)
        if (state.meshOn && state.meshPeers > 0 && meshEligible) add(if (state.bridgeHops != null) Carrier.BRIDGE else Carrier.MESH)
    }

    /** The rungs open now, for the pill and the sheet (QR and Saved are always there). */
    fun available(state: ConnectivityState): Set<Carrier> = buildSet {
        if (state.relaysReachable > 0) add(Carrier.ONLINE)
        if (state.meshOn && state.bridgeHops != null && state.meshPeers > 0) add(Carrier.BRIDGE)
        if (state.nodePeers > 0 || state.lanPeers > 0) add(Carrier.NODE)
        if (state.meshOn && state.meshPeers > 0) add(Carrier.MESH)
        add(Carrier.QR)
        add(Carrier.SAVED)
    }
}

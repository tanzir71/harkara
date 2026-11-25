package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.chat.ChatProtocol
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.wire.FrameType
import io.github.tanzir71.harkara.core.wire.MeshFrame
import kotlin.math.pow

/**
 * A radio that finds nearby phones and moves bytes to them: Bluetooth LE (dual role), Wi-Fi Aware, or a fake
 * in tests. Radios only manage links; every mesh rule lives in [MeshEngine]. Callbacks may arrive on any
 * thread, and [send] must never block (radios queue writes per link).
 */
interface MeshRadio {
    /** Short name used in peer ids ("ble", "aware", "fake"). */
    val kind: String

    /** Starts advertising [token] and looking for peers. */
    fun start(token: ByteArray, listener: Listener)

    fun stop()

    /** Duty cycle: scanning is switched off between windows in Saver mode. Links stay up. */
    fun setScanning(on: Boolean)

    /** Queues [bytes] (one encoded frame or fragment) for [peer]; higher [priority] goes first. */
    fun send(peer: String, bytes: ByteArray, priority: Int): Boolean

    interface Listener {
        /** A link is ready; frames up to [mtu] bytes fit in one write. */
        fun onPeerUp(peer: String, mtu: Int)

        fun onPeerDown(peer: String)

        fun onBytes(peer: String, bytes: ByteArray)

        /** A scan saw [peer] at [rssi] dBm (for the radar); no link implied. */
        fun onSighting(peer: String, rssi: Int) = Unit
    }
}

/**
 * Mesh mode. Saver (the default when the mesh is on) scans in short windows and keeps few links; Event, for
 * crowds and disasters, scans all the time, keeps more links and lets messages travel further.
 */
enum class MeshProfile(
    /** Most hops an event this phone starts may travel. */
    val ttl: Int,
    /** Most simultaneous links this phone dials. */
    val maxLinks: Int,
    val scanOnSeconds: Int,
    val scanOffSeconds: Int,
    /** How often presence is re-announced to linked peers. */
    val presenceSeconds: Int,
) {
    SAVER(ttl = 5, maxLinks = 4, scanOnSeconds = 10, scanOffSeconds = 50, presenceSeconds = 120),
    EVENT(ttl = 7, maxLinks = 8, scanOnSeconds = 60, scanOffSeconds = 0, presenceSeconds = 30),
    ;

    /** Whether the scan window is open [elapsedSeconds] after the mesh started. */
    fun scanning(elapsedSeconds: Long): Boolean {
        if (scanOffSeconds == 0) return true
        return elapsedSeconds.mod((scanOnSeconds + scanOffSeconds).toLong()) < scanOnSeconds
    }
}

/**
 * What a phone says about itself in a [FrameType.PRESENCE] frame: its daily presence id, whether it has
 * internet (a bridge) or runs a neighbourhood node, and how many hops away the nearest bridge is.
 *
 * Payload: 16-byte presence id | u8 flags (bit 0 internet, bit 1 node) | u8 bridge hops (255 = none).
 * A bare 16-byte id (older phones) reads as "no bridge, not a node".
 */
data class PresenceInfo(val id: ByteArray, val internet: Boolean = false, val node: Boolean = false, val bridgeHops: Int? = null) {
    init {
        require(id.size == 16) { "presence ids are 16 bytes" }
        require(bridgeHops == null || bridgeHops in 0..254) { "bridge hops out of range" }
    }

    fun frame(): MeshFrame {
        val flags = (if (internet) 1 else 0) or (if (node) 2 else 0)
        return MeshFrame(FrameType.PRESENCE, 1, 0, 0, id + byteArrayOf(flags.toByte(), (bridgeHops ?: NO_BRIDGE).toByte()))
    }

    val shortId: String get() = Hex.encode(id.copyOf(4))

    override fun equals(other: Any?): Boolean = other is PresenceInfo && other.id.contentEquals(id) &&
        other.internet == internet && other.node == node && other.bridgeHops == bridgeHops

    override fun hashCode(): Int = ((id.contentHashCode() * 31 + internet.hashCode()) * 31 + node.hashCode()) * 31 + (bridgeHops ?: -1)

    companion object {
        private const val NO_BRIDGE = 255

        fun parse(frame: MeshFrame): PresenceInfo {
            require(frame.type == FrameType.PRESENCE) { "not a presence frame" }
            val p = frame.payload
            require(p.size == 16 || p.size == 18) { "bad presence payload" }
            if (p.size == 16) return PresenceInfo(p)
            val flags = p[16].toInt() and 0xFF
            val hops = (p[17].toInt() and 0xFF).takeIf { it != NO_BRIDGE }
            return PresenceInfo(p.copyOf(16), internet = flags and 1 != 0, node = flags and 2 != 0, bridgeHops = hops)
        }
    }
}

/** Link rules shared by the radios. */
object LinkPolicy {
    /** Bytes of the presence id a radio advertises so two phones can agree who dials. */
    const val TOKEN_BYTES = 4

    /**
     * Both phones advertise and scan; only the one with the smaller token dials, so each pair ends up with
     * one link. A peer whose token we haven't read yet is not dialled.
     */
    fun shouldDial(self: ByteArray, peer: ByteArray?, links: Int, maxLinks: Int, alreadyLinked: Boolean): Boolean {
        if (peer == null || alreadyLinked || links >= maxLinks) return false
        for (i in 0 until minOf(self.size, peer.size)) {
            val a = self[i].toInt() and 0xFF
            val b = peer[i].toInt() and 0xFF
            if (a != b) return a < b
        }
        return false
    }
}

/** Which events may travel over Bluetooth/Wi-Fi Aware (handover 4 and 11). */
object MeshPolicy {
    const val MAX_EVENT_BYTES = 16 * 1024

    fun eligible(event: Event, nowSeconds: Long): Boolean {
        if (event.isExpired(nowSeconds)) return false
        if (event.toJson().length > MAX_EVENT_BYTES) return false
        // Anonymous feed photos never go over the mesh: text and category only.
        if (event.kind == Kinds.FEED_POST && event.tags.any { it.firstOrNull() in PHOTO_TAGS }) return false
        // "Everyone on this Wi-Fi" stays in the room.
        if (ChatProtocol.isWifiMessage(event)) return false
        return true
    }

    private val PHOTO_TAGS = setOf("imeta", "image", "thumb")
}

/** Radar helpers: a rough distance from BLE signal strength. */
object RangeEstimate {
    /** Log-distance path loss: −59 dBm at 1 m, exponent 2.5 (indoors, bodies, walls). Rough by nature. */
    fun meters(rssi: Int, txPowerAt1m: Int = -59, exponent: Double = 2.5): Double =
        10.0.pow((txPowerAt1m - rssi) / (10 * exponent))

    /** 0–4 signal bars. */
    fun bars(rssi: Int): Int = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
}

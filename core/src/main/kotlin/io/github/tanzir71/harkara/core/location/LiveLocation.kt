package io.github.tanzir71.harkara.core.location

import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.core.nostr.Schnorr
import io.github.tanzir71.harkara.core.nostr.UnsignedEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/** The three share lengths offered (handover §4.1); a share always stops by itself. */
enum class LiveDuration(val seconds: Long) {
    MIN_15(15 * 60L), HOUR_1(3600L), HOUR_8(8 * 3600L);

    companion object {
        val MAX: Long = HOUR_8.seconds
    }
}

@Serializable
data class LivePointBody(val lat: Double, val lng: Double, val acc: Int? = null)

/**
 * Live location (kind 23471, docs/PROTOCOL.md §16). A share has a fresh **session key**
 * (`KeyRing.session(shareId)`) and an end time:
 *
 * - **start**: rumor by the profile key, `["share", id]`, `["session", sessionPubkey, binding]`,
 *   `["end", unix]`, `["p", …]` per person, optional `["name", …]`; the binding is the session key's
 *   BIP-340 signature over SHA-256(`harkara-live|<owner>|<share>|<end>`), so a session key can't be
 *   claimed by anyone else. Sent as a normal gift wrap (1059) with NIP-40 `expiration` = end, so a phone
 *   that comes online later still learns the key.
 * - **point**: rumor by the session key, `["share", id]`, `["seq", n]` (1, 2, 3 …), `["end", unix]`,
 *   content `{"lat","lng","acc"}`; sent as ephemeral gift wraps (21059) that relays don't store.
 * - **stop**: a point with `["status", "stopped"]` (no position).
 */
object LiveShares {
    const val KIND = Kinds.LIVE_LOCATION
    private val json = Json { ignoreUnknownKeys = true }

    fun binding(owner: String, shareId: String, end: Long): ByteArray =
        Hashes.sha256("harkara-live|$owner|$shareId|$end".encodeToByteArray())

    fun startRumor(profile: KeyPair, session: KeyPair, shareId: String, recipients: List<String>, end: Long, createdAt: Long, name: String? = null): Rumor {
        require(end > createdAt && end - createdAt <= LiveDuration.MAX + 60) { "a share lasts at most 8 hours" }
        val proof = Hex.encode(session.sign(binding(profile.pubkeyHex, shareId, end)))
        val tags = buildList {
            add(listOf("share", shareId))
            add(listOf("session", session.pubkeyHex, proof))
            add(listOf("end", end.toString()))
            recipients.distinct().filter { it != profile.pubkeyHex }.forEach { add(listOf("p", it)) }
            name?.takeIf { it.isNotBlank() }?.let { add(listOf("name", it.trim().take(40))) }
        }
        return UnsignedEvent(profile.pubkeyHex, createdAt, KIND, tags, "")
    }

    fun startWraps(profile: KeyPair, session: KeyPair, shareId: String, recipients: List<String>, end: Long, createdAt: Long, name: String? = null): List<Event> {
        val rumor = startRumor(profile, session, shareId, recipients, end, createdAt, name)
        return recipients.distinct().filter { it != profile.pubkeyHex }.map { to ->
            GiftWrap.wrap(GiftWrap.seal(rumor, profile, to), to, extraTags = listOf(listOf("expiration", end.toString())))
        }
    }

    fun pointRumor(session: KeyPair, shareId: String, seq: Long, end: Long, at: LatLng?, accuracyMeters: Int?, createdAt: Long, stopped: Boolean = false): Rumor {
        require(seq > 0) { "seq starts at 1" }
        val tags = buildList {
            add(listOf("share", shareId))
            add(listOf("seq", seq.toString()))
            add(listOf("end", end.toString()))
            if (stopped) add(listOf("status", "stopped"))
        }
        val content = if (at == null) "" else json.encodeToString(LivePointBody.serializer(), LivePointBody(round5(at.lat), round5(at.lng), accuracyMeters))
        return UnsignedEvent(session.pubkeyHex, createdAt, KIND, tags, content)
    }

    /** Ephemeral wraps (21059) of one point for every person. */
    fun pointWraps(session: KeyPair, recipients: List<String>, rumor: Rumor): List<Event> =
        recipients.distinct().map { to -> GiftWrap.sealAndWrap(rumor, session, to, Kinds.EPHEMERAL_GIFT_WRAP) }

    fun parseBody(content: String): LivePointBody? = runCatching { json.decodeFromString(LivePointBody.serializer(), content) }.getOrNull()
        ?.takeIf { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }

    private fun round5(v: Double) = String.format(Locale.ROOT, "%.5f", v).toDouble()
}

/** Someone sharing their location with this phone. */
data class LivePerson(
    val owner: String,
    val shareId: String,
    val name: String?,
    val end: Long,
    val position: LatLng?,
    val accuracyMeters: Int?,
    val updatedAt: Long?,
    val stopped: Boolean,
)

/** Why a live-location message was taken or dropped. */
enum class LiveVerdict { STARTED, EXTENDED, ACCEPTED, STOPPED, NOT_LIVE, BAD_START, FOREIGN_SESSION, UNKNOWN_SESSION, REPLAY, ENDED, MISMATCH, BAD_POINT }

/**
 * Receiver side with replay protection: points count only from a session key a verified start bound to
 * its owner, only with a `seq` above the last one seen, and only up to the share's end. Everything else
 * is dropped.
 */
class LiveShareTracker(private val now: () -> Long) {
    private data class Session(
        val owner: String,
        val shareId: String,
        val name: String?,
        var end: Long,
        var lastSeq: Long = 0,
        var position: LatLng? = null,
        var accuracy: Int? = null,
        var updatedAt: Long? = null,
        var stopped: Boolean = false,
    )

    private val sessions = LinkedHashMap<String, Session>()
    /** Session keys whose stop arrived (possibly before their start, when a relay re-sends stored wraps). */
    private val stoppedKeys = LinkedHashSet<String>()

    /** A rumor opened from a gift wrap addressed to us (its author is authenticated by the seal). */
    @Synchronized
    fun accept(rumor: Rumor): LiveVerdict {
        if (rumor.kind != LiveShares.KIND) return LiveVerdict.NOT_LIVE
        return if (rumor.tags.any { it.size > 1 && it[0] == "session" }) start(rumor) else point(rumor)
    }

    private fun start(rumor: Rumor): LiveVerdict {
        val shareId = rumor.tagValue("share")?.takeIf { it.isNotBlank() } ?: return LiveVerdict.BAD_START
        val sessionTag = rumor.tags.first { it.size > 1 && it[0] == "session" }
        val sessionKey = sessionTag[1]
        val proof = sessionTag.getOrNull(2) ?: return LiveVerdict.BAD_START
        val end = rumor.tagValue("end")?.toLongOrNull() ?: return LiveVerdict.BAD_START
        if (end - rumor.createdAt > LiveDuration.MAX + 60) return LiveVerdict.BAD_START
        if (!Schnorr.verify(proof, LiveShares.binding(rumor.pubkey, shareId, end), sessionKey)) return LiveVerdict.BAD_START
        if (end <= now() || sessionKey in stoppedKeys) return LiveVerdict.ENDED
        val existing = sessions[sessionKey]
        if (existing != null) {
            if (existing.owner != rumor.pubkey || existing.shareId != shareId) return LiveVerdict.FOREIGN_SESSION
            if (end <= existing.end) return LiveVerdict.REPLAY
            existing.end = end
            return LiveVerdict.EXTENDED
        }
        sessions[sessionKey] = Session(rumor.pubkey, shareId, rumor.tagValue("name"), end)
        return LiveVerdict.STARTED
    }

    private fun point(rumor: Rumor): LiveVerdict {
        val s = sessions[rumor.pubkey]
        if (s == null) {
            // Only the session key's holder can seal this, so remembering it can't hide anyone else's share.
            if (rumor.tagValue("status") == "stopped") {
                stoppedKeys += rumor.pubkey
                if (stoppedKeys.size > 512) stoppedKeys.remove(stoppedKeys.first())
                return LiveVerdict.STOPPED
            }
            return LiveVerdict.UNKNOWN_SESSION
        }
        if (rumor.tagValue("share") != s.shareId) return LiveVerdict.MISMATCH
        val seq = rumor.tagValue("seq")?.toLongOrNull() ?: return LiveVerdict.BAD_POINT
        val end = rumor.tagValue("end")?.toLongOrNull() ?: return LiveVerdict.BAD_POINT
        if (end != s.end) return LiveVerdict.MISMATCH
        val t = now()
        if (s.stopped || t > s.end || rumor.createdAt > s.end) return LiveVerdict.ENDED
        if (seq <= s.lastSeq) return LiveVerdict.REPLAY
        if (rumor.tagValue("status") == "stopped") {
            s.lastSeq = seq
            s.stopped = true
            stoppedKeys += rumor.pubkey
            return LiveVerdict.STOPPED
        }
        val body = LiveShares.parseBody(rumor.content) ?: return LiveVerdict.BAD_POINT
        s.lastSeq = seq
        s.position = LatLng(body.lat, body.lng)
        s.accuracy = body.acc
        s.updatedAt = rumor.createdAt
        return LiveVerdict.ACCEPTED
    }

    /** Shares still running, newest first; ended ones are forgotten, stopped ones are kept (hidden) until their end. */
    @Synchronized
    fun active(): List<LivePerson> {
        val t = now()
        sessions.entries.removeAll { it.value.end < t }
        return sessions.values.filterNot { it.stopped }.map { LivePerson(it.owner, it.shareId, it.name, it.end, it.position, it.accuracy, it.updatedAt, it.stopped) }
            .sortedByDescending { it.updatedAt ?: 0 }
    }

    @Synchronized
    fun lastSeq(sessionKey: String): Long? = sessions[sessionKey]?.lastSeq
}

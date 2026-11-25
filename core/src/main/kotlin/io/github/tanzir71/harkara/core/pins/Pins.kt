package io.github.tanzir71.harkara.core.pins

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Nip44
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.core.nostr.UnsignedEvent
import io.github.tanzir71.harkara.core.nostr.signEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Who can see a pin. */
enum class PinVisibility(val wire: String) {
    /** NIP-78 app data (kind 30078) encrypted to the owner's own key. */
    PRIVATE("private"),
    /** A kind 33471 rumor gift-wrapped to named people (and the owner). */
    SHARED("shared"),
    /** A signed kind 33471 event with `g` tags anyone in the area can read and answer "still there?". */
    PUBLIC("public");

    companion object {
        fun of(wire: String?): PinVisibility? = entries.firstOrNull { it.wire == wire }
    }
}

/** A saved place. [id] is the `d` tag; the newest version per (kind, owner, id) wins. */
data class Pin(
    val id: String,
    val owner: String,
    val visibility: PinVisibility,
    val title: String,
    val note: String,
    val position: LatLng,
    val color: Category = Category.STOCK,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val recipients: List<String> = emptyList(),
    val deleted: Boolean = false,
    /** Id of the event (or rumor) this version came from. */
    val eventId: String = "",
) {
    val kind: Int get() = if (visibility == PinVisibility.PRIVATE) Kinds.APP_DATA else Kinds.PIN
    /** NIP-01 `a` coordinate, what "still there?" reactions point at. */
    val address: String get() = Pins.address(kind, owner, id)

    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now
}

/** "Still there?" answers on a pin. */
data class PinTally(val yes: Int, val no: Int, val mine: Boolean? = null)

@Serializable
private data class PrivatePinBody(
    val type: String = Pins.BODY_TYPE,
    val title: String,
    val note: String = "",
    val lat: Double,
    val lng: Double,
    val color: String = Category.STOCK.tag,
    val deleted: Boolean = false,
)

/**
 * Pin events (docs/PROTOCOL.md §15):
 * - private: kind 30078, `["d", id]` only; content NIP-44 encrypted to the owner (title, note, lat/lng, colour);
 * - shared: kind 33471 rumor, same tags as public plus `p` per person, gift-wrapped (1059) to each and to the owner;
 * - public: kind 33471 signed by the profile key: `d`, `title`, `g` at precision 9/6/5 (the 9-character cell
 *   is the pin's spot), `color`, `visibility`, NIP-40 `expiration`; content = the note;
 * - "still there?": kind 7 with content `yes`/`no`, `["a", "33471:<owner>:<d>"]`, `p` owner, `k` 33471.
 * Deleting publishes a newer version with `["status", "deleted"]`.
 */
object Pins {
    const val BODY_TYPE = "harkara-pin"
    const val MAX_TITLE = 80
    const val MAX_NOTE = 400
    const val DEFAULT_PUBLIC_TTL: Long = 7 * 86_400
    /** Answers older than this don't count towards "still there?". */
    const val VOTE_WINDOW: Long = 7 * 86_400

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun address(kind: Int, owner: String, id: String) = "$kind:$owner:$id"

    // ---- building -------------------------------------------------------------------------------

    fun privateEvent(owner: KeyPair, pin: Pin, createdAt: Long = pin.createdAt): Event {
        require(pin.visibility == PinVisibility.PRIVATE && pin.owner == owner.pubkeyHex) { "a private pin belongs to its signer" }
        val body = PrivatePinBody(BODY_TYPE, pin.title.take(MAX_TITLE), pin.note.take(MAX_NOTE), round(pin.position.lat), round(pin.position.lng), pin.color.tag, pin.deleted)
        val content = Nip44.encrypt(json.encodeToString(PrivatePinBody.serializer(), body), selfKey(owner))
        return owner.signEvent(Kinds.APP_DATA, listOf(listOf("d", pin.id)), content, createdAt)
    }

    private fun pinTags(pin: Pin, createdAt: Long): List<List<String>> = buildList {
        add(listOf("d", pin.id))
        add(listOf("title", pin.title.trim().take(MAX_TITLE)))
        listOf(9, 6, 5).forEach { add(listOf("g", GeoHash.encode(pin.position.lat, pin.position.lng, it))) }
        add(listOf("color", pin.color.tag))
        add(listOf("visibility", pin.visibility.wire))
        if (pin.deleted) add(listOf("status", "deleted"))
        val expires = if (pin.deleted) createdAt + 86_400 else pin.expiresAt
        expires?.let { add(listOf("expiration", it.toString())) }
    }

    fun publicEvent(owner: KeyPair, pin: Pin, createdAt: Long = pin.createdAt): Event {
        require(pin.visibility == PinVisibility.PUBLIC && pin.owner == owner.pubkeyHex) { "a public pin belongs to its signer" }
        require(pin.title.isNotBlank()) { "a pin needs a title" }
        return owner.signEvent(Kinds.PIN, pinTags(pin, createdAt), pin.note.trim().take(MAX_NOTE), createdAt)
    }

    /** The shared pin rumor (unsigned; its seal is signed by the owner). */
    fun sharedRumor(owner: KeyPair, pin: Pin, createdAt: Long = pin.createdAt): Rumor {
        require(pin.visibility == PinVisibility.SHARED && pin.owner == owner.pubkeyHex) { "a shared pin belongs to its sender" }
        val tags = pinTags(pin, createdAt) + pin.recipients.distinct().filter { it != owner.pubkeyHex }.map { listOf("p", it) }
        return UnsignedEvent(owner.pubkeyHex, createdAt, Kinds.PIN, tags, pin.note.trim().take(MAX_NOTE))
    }

    /** One gift wrap per person plus one to the owner; wraps expire with the pin so relays can drop them. */
    fun sharedWraps(owner: KeyPair, pin: Pin, createdAt: Long = pin.createdAt): List<Event> {
        val rumor = sharedRumor(owner, pin, createdAt)
        val expiry = rumor.tagValue("expiration")
        return (pin.recipients + owner.pubkeyHex).distinct().map { to ->
            GiftWrap.wrap(GiftWrap.seal(rumor, owner, to), to, extraTags = listOfNotNull(expiry?.let { listOf("expiration", it) }))
        }
    }

    /** "Still there?" answer from [voter] (the area key, so answers stay anonymous). */
    fun stillThere(voter: KeyPair, pin: Pin, yes: Boolean, createdAt: Long): Event =
        voter.signEvent(
            Kinds.REACTION,
            listOf(listOf("a", pin.address), listOf("p", pin.owner), listOf("k", Kinds.PIN.toString())),
            if (yes) "yes" else "no",
            createdAt,
        )

    // ---- reading --------------------------------------------------------------------------------

    /** A private pin, readable only by its owner. */
    fun parsePrivate(event: Event, owner: KeyPair): Pin? {
        if (event.kind != Kinds.APP_DATA || event.pubkey != owner.pubkeyHex || !event.verify()) return null
        val d = event.tagValue("d") ?: return null
        val body = runCatching {
            json.decodeFromString(PrivatePinBody.serializer(), Nip44.decrypt(event.content, selfKey(owner)))
        }.getOrNull() ?: return null
        if (body.type != BODY_TYPE || body.lat !in -90.0..90.0 || body.lng !in -180.0..180.0) return null
        return Pin(
            d, event.pubkey, PinVisibility.PRIVATE, body.title.take(MAX_TITLE), body.note.take(MAX_NOTE), LatLng(body.lat, body.lng),
            Category.fromTag(body.color) ?: Category.STOCK, event.createdAt, event.expiration, emptyList(), body.deleted, event.id,
        )
    }

    /** A public pin from a relay; null unless signed, well-formed and not expired. */
    fun parsePublic(event: Event, now: Long): Pin? {
        if (event.kind != Kinds.PIN || !event.verify() || event.isExpired(now)) return null
        return fromPinTags(event.pubkey, event.createdAt, event.tags, event.content, event.id, PinVisibility.PUBLIC)
    }

    /** A shared pin from an opened gift wrap (the rumor's author is its owner, checked by the unwrap). */
    fun parseShared(rumor: Rumor, now: Long): Pin? {
        if (rumor.kind != Kinds.PIN) return null
        if (rumor.tagValue("expiration")?.toLongOrNull()?.let { it <= now } == true) return null
        return fromPinTags(rumor.pubkey, rumor.createdAt, rumor.tags, rumor.content, rumor.id, PinVisibility.SHARED)
    }

    private fun fromPinTags(owner: String, createdAt: Long, tags: List<List<String>>, content: String, id: String, expected: PinVisibility): Pin? {
        fun value(name: String) = tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)
        if (PinVisibility.of(value("visibility")) != expected) return null
        val d = value("d")?.takeIf { it.isNotBlank() } ?: return null
        val title = value("title")?.takeIf { it.isNotBlank() } ?: return null
        val cell = tags.filter { it.size > 1 && it[0] == "g" }.map { it[1] }.filter(GeoHash::isValid).maxByOrNull { it.length } ?: return null
        if (cell.length < 7) return null
        return Pin(
            id = d,
            owner = owner,
            visibility = expected,
            title = title.take(MAX_TITLE),
            note = content.take(MAX_NOTE),
            position = GeoMath.decode(cell),
            color = value("color")?.let(Category::fromTag) ?: Category.STOCK,
            createdAt = createdAt,
            expiresAt = value("expiration")?.toLongOrNull(),
            recipients = tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] },
            deleted = value("status") == "deleted",
            eventId = id,
        )
    }

    /** Replaceable semantics: the newer version wins; on a tie the lower id wins (NIP-01). */
    fun newer(a: Pin, b: Pin): Pin = when {
        a.createdAt != b.createdAt -> if (a.createdAt > b.createdAt) a else b
        else -> if (a.eventId <= b.eventId) a else b
    }

    /** Folds any number of versions into the latest per address, dropping deleted and expired pins. */
    fun latest(versions: List<Pin>, now: Long): List<Pin> =
        versions.groupBy { it.address }.values.map { it.reduce(::newer) }.filter { !it.deleted && !it.isExpired(now) }

    /** A reaction that answers "still there?" for [pin]: (voter, yes?) or null. */
    fun answer(reaction: Event, pin: Pin): Pair<String, Boolean>? {
        if (reaction.kind != Kinds.REACTION || reaction.tagValue("a") != pin.address) return null
        val yes = when (reaction.content) { "yes" -> true; "no" -> false; else -> return null }
        return reaction.pubkey to yes
    }

    /** Latest answer per key within [VOTE_WINDOW]; [myKeys] marks which way this phone answered. */
    fun tally(pin: Pin, reactions: List<Event>, now: Long, myKeys: Set<String> = emptySet()): PinTally {
        val latest = reactions.filter { it.createdAt > now - VOTE_WINDOW && it.createdAt <= now + 900 }
            .mapNotNull { r -> answer(r, pin)?.let { r to it } }
            .groupBy { it.second.first }
            .mapValues { (_, list) -> list.maxWith(compareBy<Pair<Event, Pair<String, Boolean>>> { it.first.createdAt }.thenByDescending { it.first.id }).second.second }
        return PinTally(latest.values.count { it }, latest.values.count { !it }, latest.entries.firstOrNull { it.key in myKeys }?.value)
    }

    private fun selfKey(owner: KeyPair) = Nip44.conversationKey(owner.privateKey, owner.pubkeyHex)

    private fun round(v: Double) = Math.round(v * 1e6) / 1e6
}

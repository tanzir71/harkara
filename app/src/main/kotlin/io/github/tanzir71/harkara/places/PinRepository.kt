package io.github.tanzir71.harkara.places

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.core.pins.ImportedPlace
import io.github.tanzir71.harkara.core.pins.Pin
import io.github.tanzir71.harkara.core.pins.PinFileFormat
import io.github.tanzir71.harkara.core.pins.PinFiles
import io.github.tanzir71.harkara.core.pins.PinTally
import io.github.tanzir71.harkara.core.pins.PinVisibility
import io.github.tanzir71.harkara.core.pins.Pins
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.PinEntity
import io.github.tanzir71.harkara.data.PinVoteEntity
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/** A pin with its "still there?" answers, as the map and lists show it. */
data class PinView(val pin: Pin, val tally: PinTally, val mine: Boolean)

/**
 * Saved pins: private (NIP-78, encrypted to ourselves), shared (gift-wrapped to named people) and public
 * (signed, `g`-tagged, with "still there?" answers). One row per pin address keeps only the newest
 * version, so edits from any phone replace older ones.
 */
class PinRepository(
    private val db: HarkaraDatabase,
    private val outbox: Outbox,
    private val pool: RelayPool,
    private val profile: () -> KeyPair,
    /** The weekly area key for a geohash-5 cell: "still there?" answers stay anonymous like feed votes. */
    private val areaKey: (String) -> KeyPair,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val now get() = nowMillis() / 1000

    fun pins(): Flow<List<PinView>> = combine(db.pinDao().observeAll(), db.pinDao().observeVotes()) { rows, votes ->
        val me = runCatching { profile().pubkeyHex }.getOrNull()
        val t = now
        val byAddress = votes.groupBy { it.address }
        rows.mapNotNull { row -> row.toPin()?.takeIf { !it.isExpired(t) } }.map { pin ->
            val events = byAddress[pin.address].orEmpty().mapNotNull { runCatching { Event.fromJson(it.json) }.getOrNull() }
            val mineKeys = byAddress[pin.address].orEmpty().filter { it.own }.map { it.voter }.toSet()
            PinView(pin, Pins.tally(pin, events, t, mineKeys), pin.owner == me)
        }
    }

    fun startIngest(scope: CoroutineScope, inbox: GiftInbox) {
        inbox.register(setOf(Kinds.PIN)) { rumor, _ -> ingestShared(rumor) }
        scope.launch { pool.incoming.collect { ingest(it.event) } }
    }

    @Volatile private var center: LatLng? = null

    /** Public pins around [center], our own private pins, and answers on the pins we hold. */
    suspend fun subscribe(center: LatLng) {
        this.center = center
        val cells = GeoMath.withNeighbours(GeoHash.encode(center.lat, center.lng, 5))
        val me = profile().pubkeyHex
        val addresses = db.pinDao().observeAll().first().filter { it.visibility == PinVisibility.PUBLIC.wire }.map { it.address }.take(200)
        pool.subscribe(
            SUB_PINS,
            buildList {
                add(Filter(kinds = listOf(Kinds.PIN), tags = mapOf("g" to cells), limit = 500))
                add(Filter(kinds = listOf(Kinds.APP_DATA, Kinds.PIN), authors = listOf(me)))
                if (addresses.isNotEmpty()) add(Filter(kinds = listOf(Kinds.REACTION), tags = mapOf("a" to addresses), since = now - Pins.VOTE_WINDOW))
            },
        )
    }

    suspend fun ingest(event: Event): Boolean = when (event.kind) {
        Kinds.PIN -> Pins.parsePublic(event, now)?.let { store(it, event.toJson()) } ?: false
        Kinds.APP_DATA -> if (event.pubkey == profile().pubkeyHex) Pins.parsePrivate(event, profile())?.let { store(it, event.toJson()) } ?: false else false
        Kinds.REACTION -> ingestVote(event)
        else -> false
    }

    suspend fun ingestShared(rumor: Rumor): Boolean {
        val pin = Pins.parseShared(rumor, now) ?: return false
        val me = profile().pubkeyHex
        if (pin.owner != me && me !in pin.recipients) return false
        return store(pin, rumor.toJson())
    }

    private suspend fun ingestVote(event: Event): Boolean {
        val address = event.tagValue("a") ?: return false
        if (!address.startsWith("${Kinds.PIN}:")) return false
        val row = db.pinDao().get(address) ?: return false
        val pin = row.toPin() ?: return false
        val (voter, yes) = Pins.answer(event, pin) ?: return false
        if (event.createdAt > now + 900 || !event.verify()) return false
        val old = db.pinDao().vote(address, voter)
        if (old != null && old.createdAt >= event.createdAt) return false
        db.pinDao().upsertVote(PinVoteEntity(address, voter, yes, event.createdAt, event.toJson(), old?.own ?: false))
        return true
    }

    /** Keeps [pin] if it is newer than what we hold for its address. */
    private suspend fun store(pin: Pin, json: String): Boolean {
        val old = db.pinDao().get(pin.address)?.toPin()
        if (old != null && Pins.newer(old, pin) === old && old.eventId != pin.eventId) return false
        if (old != null && old.eventId == pin.eventId) return false
        db.pinDao().upsert(pin.toEntity(json, own = pin.owner == profile().pubkeyHex, updatedAt = nowMillis()))
        // A public pin we didn't hold before: follow its "still there?" answers too.
        if (old == null && pin.visibility == PinVisibility.PUBLIC) center?.let { subscribe(it) }
        return true
    }

    /** Saves a new pin or a new version of one of ours, and sends it on its way. */
    suspend fun save(
        title: String,
        note: String,
        at: LatLng,
        color: Category,
        visibility: PinVisibility,
        recipients: List<String> = emptyList(),
        ttlSeconds: Long? = null,
        id: String = UUID.randomUUID().toString(),
    ): Pin {
        val keys = profile()
        val t = now
        val old = db.pinDao().get(Pins.address(if (visibility == PinVisibility.PRIVATE) Kinds.APP_DATA else Kinds.PIN, keys.pubkeyHex, id))
        val createdAt = maxOf(t, (old?.createdAt ?: 0) + 1)
        val expires = when (visibility) {
            PinVisibility.PUBLIC -> createdAt + (ttlSeconds ?: Pins.DEFAULT_PUBLIC_TTL)
            else -> ttlSeconds?.let { createdAt + it }
        }
        val pin = Pin(id, keys.pubkeyHex, visibility, title.trim(), note.trim(), at, color, createdAt, expires, recipients.distinct())
        return publish(pin)
    }

    private suspend fun publish(pin: Pin): Pin {
        val keys = profile()
        when (pin.visibility) {
            PinVisibility.PRIVATE -> {
                val event = Pins.privateEvent(keys, pin)
                store(pin.copy(eventId = event.id), event.toJson())
                outbox.enqueue(event, Priority.TASK)
            }
            PinVisibility.PUBLIC -> {
                val event = Pins.publicEvent(keys, pin)
                store(pin.copy(eventId = event.id), event.toJson())
                outbox.enqueue(event, Priority.POST)
            }
            PinVisibility.SHARED -> {
                val rumor = Pins.sharedRumor(keys, pin)
                store(pin.copy(eventId = rumor.id), rumor.toJson())
                Pins.sharedWraps(keys, pin).forEach { outbox.enqueue(it, Priority.MESSAGE) }
            }
        }
        return pin
    }

    /** Publishes a deleted version (newer), so every phone holding the pin drops it. */
    suspend fun delete(pin: Pin) {
        if (pin.owner != profile().pubkeyHex) return
        publish(pin.copy(createdAt = maxOf(now, pin.createdAt + 1), deleted = true, expiresAt = null))
    }

    /** "Still there?" yes/no on someone's public pin, from this week's area key. */
    suspend fun answer(pin: Pin, yes: Boolean) {
        if (pin.visibility != PinVisibility.PUBLIC) return
        val key = areaKey(GeoHash.encode(pin.position.lat, pin.position.lng, 5))
        val old = db.pinDao().vote(pin.address, key.pubkeyHex)
        val event = Pins.stillThere(key, pin, yes, maxOf(now, (old?.createdAt ?: 0) + 1))
        db.pinDao().upsertVote(PinVoteEntity(pin.address, key.pubkeyHex, yes, event.createdAt, event.toJson(), own = true))
        outbox.enqueue(event, Priority.REACTION)
    }

    /** Imports places from a GPX/KML/GeoJSON file as private pins. Returns how many were added. */
    suspend fun importFile(name: String, text: String): Int {
        val format = PinFileFormat.guess(name, text) ?: return 0
        val places: List<ImportedPlace> = PinFiles.import(text, format)
        places.forEach { save(it.title, it.note, it.position, Category.STOCK, PinVisibility.PRIVATE) }
        return places.size
    }

    suspend fun export(format: PinFileFormat): String =
        PinFiles.export(db.pinDao().mine().mapNotNull { it.toPin() }.filter { !it.isExpired(now) }, format)

    companion object {
        const val SUB_PINS = "harkara-pins"
    }
}

private fun Pin.toEntity(json: String, own: Boolean, updatedAt: Long) = PinEntity(
    address = address, id = id, owner = owner, visibility = visibility.wire, title = title, note = note,
    lat = position.lat, lng = position.lng, color = color.tag, createdAt = createdAt, expiresAt = expiresAt,
    deleted = deleted, recipients = recipients.joinToString("\n"), eventId = eventId,
    geohash5 = GeoHash.encode(position.lat, position.lng, 5), json = json, own = own, updatedAt = updatedAt,
)

internal fun PinEntity.toPin(): Pin? {
    val v = PinVisibility.of(visibility) ?: return null
    return Pin(
        id, owner, v, title, note, LatLng(lat, lng), Category.fromTag(color) ?: Category.STOCK, createdAt, expiresAt,
        recipients.split('\n').filter { it.isNotBlank() }, deleted, eventId,
    )
}

package io.github.tanzir71.harkara.core.nostr

import kotlinx.serialization.json.JsonPrimitive

/** NIP-01 event id: SHA-256 of the canonical `[0,pubkey,created_at,kind,tags,content]` serialization. */
object EventId {
    /** The canonical serialization as a string (UTF-8 encode it to hash). */
    fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val out = StringBuilder(128 + content.length)
        out.append("[0,")
        NostrJson.quote(pubkey, out)
        out.append(',').append(createdAt).append(',').append(kind).append(',')
        NostrJson.tags(tags, out)
        out.append(',')
        NostrJson.quote(content, out)
        out.append(']')
        return out.toString()
    }

    fun computeBytes(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): ByteArray =
        Hashes.sha256(serialize(pubkey, createdAt, kind, tags, content).encodeToByteArray())

    fun compute(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
        computeBytes(pubkey, createdAt, kind, tags, content).toHex()
}

/** Outcome of [Event.check]. */
enum class EventCheck { OK, MALFORMED, TOO_LARGE, BAD_ID, BAD_SIGNATURE }

/**
 * A signed NIP-01 event: the one envelope for every post, message, task, pin and IOU. Relays and LAN carry
 * [toJson]; Bluetooth and SMS carry [io.github.tanzir71.harkara.core.wire.BinaryEventCodec] of the same fields.
 */
data class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** First value of the first tag named [name], e.g. `tagValue("g")`. */
    fun tagValue(name: String): String? = tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)

    /** Values of every tag named [name], e.g. all `p` recipients. */
    fun tagValues(name: String): List<String> = tags.filter { it.size > 1 && it[0] == name }.map { it[1] }

    /** NIP-40 expiration (unix seconds), or null when the event never expires. */
    val expiration: Long?
        get() = tagValue("expiration")?.toLongOrNull()

    fun isExpired(nowSeconds: Long): Boolean = expiration?.let { it <= nowSeconds } ?: false

    fun toJson(): String {
        val out = StringBuilder(256 + content.length)
        out.append("{\"id\":")
        NostrJson.quote(id, out)
        out.append(",\"pubkey\":")
        NostrJson.quote(pubkey, out)
        out.append(",\"created_at\":").append(createdAt)
        out.append(",\"kind\":").append(kind)
        out.append(",\"tags\":")
        NostrJson.tags(tags, out)
        out.append(",\"content\":")
        NostrJson.quote(content, out)
        out.append(",\"sig\":")
        NostrJson.quote(sig, out)
        out.append('}')
        return out.toString()
    }

    /** Size of [toJson] in UTF-8 bytes. */
    fun jsonSize(): Int = toJson().encodeToByteArray().size

    /** Recomputes the id from the fields. */
    fun computeId(): String = EventId.compute(pubkey, createdAt, kind, tags, content)

    /** True when the id matches the fields and the BIP-340 signature is valid for [pubkey]. */
    fun verify(): Boolean {
        if (!Hex.isLowerHex(id, 32) || !Hex.isLowerHex(pubkey, 32) || !Hex.isLowerHex(sig, 64)) return false
        val computed = EventId.computeBytes(pubkey, createdAt, kind, tags, content)
        if (computed.toHex() != id) return false
        return Schnorr.verify(sig.hexToBytes(), computed, pubkey.hexToBytes())
    }

    /** Full check in order: shape, size (< 16 KB of JSON), id, signature. */
    fun check(): EventCheck = when {
        !isWellFormed() -> EventCheck.MALFORMED
        jsonSize() >= MAX_JSON_BYTES -> EventCheck.TOO_LARGE
        computeId() != id -> EventCheck.BAD_ID
        !Schnorr.verify(sig.hexToBytes(), id.hexToBytes(), pubkey.hexToBytes()) -> EventCheck.BAD_SIGNATURE
        else -> EventCheck.OK
    }

    /** True when [check] passes. Peers drop anything else before storing or relaying it. */
    fun validate(): Boolean = check() == EventCheck.OK

    private fun isWellFormed(): Boolean =
        Hex.isLowerHex(id, 32) && Hex.isLowerHex(pubkey, 32) && Hex.isLowerHex(sig, 64) &&
            kind in 0..MAX_KIND && createdAt >= 0

    companion object {
        /** Events must stay under 16 KB of JSON (handover §4). */
        const val MAX_JSON_BYTES: Int = 16 * 1024

        /** Kinds are 16-bit in NIP-01. */
        const val MAX_KIND: Int = 65_535

        /** Parses event JSON. Throws [IllegalArgumentException] when a field is missing or has the wrong type. */
        fun fromJson(json: String): Event {
            val obj = try {
                NostrJson.parseObject(json)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid JSON", e)
            }
            val kind = NostrJson.long(obj, "kind")
            require(kind in 0..MAX_KIND) { "kind out of range" }
            return Event(
                id = NostrJson.string(obj, "id"),
                pubkey = NostrJson.string(obj, "pubkey"),
                createdAt = NostrJson.long(obj, "created_at"),
                kind = kind.toInt(),
                tags = NostrJson.tags(obj["tags"]),
                content = NostrJson.string(obj, "content"),
                sig = NostrJson.string(obj, "sig"),
            )
        }
    }
}

/**
 * An event before signing: a template for [KeyPair.signEvent], the input to NIP-13 mining, and (with its id)
 * a NIP-59 rumor, which is never signed.
 */
data class UnsignedEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
) {
    val id: String by lazy { EventId.compute(pubkey, createdAt, kind, tags, content) }

    fun tagValue(name: String): String? = tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)

    fun tagValues(name: String): List<String> = tags.filter { it.size > 1 && it[0] == name }.map { it[1] }

    /** Signs with [keys], which must own [pubkey]. */
    fun sign(keys: KeyPair): Event {
        require(keys.pubkeyHex == pubkey) { "pubkey does not match the signing key" }
        val idBytes = EventId.computeBytes(pubkey, createdAt, kind, tags, content)
        return Event(idBytes.toHex(), pubkey, createdAt, kind, tags, content, keys.sign(idBytes).toHex())
    }

    /** Rumor JSON: the event with its id and no `sig` (NIP-59). */
    fun toJson(): String {
        val out = StringBuilder(256 + content.length)
        out.append("{\"id\":")
        NostrJson.quote(id, out)
        out.append(",\"pubkey\":")
        NostrJson.quote(pubkey, out)
        out.append(",\"created_at\":").append(createdAt)
        out.append(",\"kind\":").append(kind)
        out.append(",\"tags\":")
        NostrJson.tags(tags, out)
        out.append(",\"content\":")
        NostrJson.quote(content, out)
        out.append('}')
        return out.toString()
    }

    companion object {
        /**
         * Parses rumor JSON. When an `id` is present it must match the fields; a `sig` is refused because a
         * rumor that can be verified loses its deniability.
         */
        fun fromJson(json: String): UnsignedEvent {
            val obj = try {
                NostrJson.parseObject(json)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid JSON", e)
            }
            require(obj["sig"] == null || (obj["sig"] as? JsonPrimitive)?.content.isNullOrEmpty()) { "a rumor must not be signed" }
            val kind = NostrJson.long(obj, "kind")
            require(kind in 0..Event.MAX_KIND) { "kind out of range" }
            val event = UnsignedEvent(
                pubkey = NostrJson.string(obj, "pubkey"),
                createdAt = NostrJson.long(obj, "created_at"),
                kind = kind.toInt(),
                tags = NostrJson.tags(obj["tags"]),
                content = NostrJson.string(obj, "content"),
            )
            require(Hex.isLowerHex(event.pubkey, 32)) { "invalid pubkey" }
            if (obj["id"] != null) require(NostrJson.string(obj, "id") == event.id) { "rumor id does not match its fields" }
            return event
        }
    }
}

/** A NIP-59 rumor: an unsigned event with an id. */
typealias Rumor = UnsignedEvent

/** Current unix time in seconds. */
fun nowSeconds(): Long = System.currentTimeMillis() / 1000

/** Signs a new event with this key. */
fun KeyPair.signEvent(
    kind: Int,
    tags: List<List<String>> = emptyList(),
    content: String = "",
    createdAt: Long = nowSeconds(),
): Event = UnsignedEvent(pubkeyHex, createdAt, kind, tags, content).sign(this)

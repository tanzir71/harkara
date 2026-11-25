package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.Hex
import java.util.Base64

/**
 * The QR rung: any event as `harkara:e:<base64url(binary event)>` (the [BinaryEventCodec] form without the
 * id, which the reader recomputes), or, when that is too long for one comfortable code, a run of
 * `harkara:q:<set>:<i>/<n>:<chunk>` codes that [QrAssembler] puts back together in any order.
 */
object QrTransport {
    const val EVENT_PREFIX = "harkara:e:"
    const val PART_PREFIX = "harkara:q:"

    /** Characters per code: about QR version 20 at error correction M, easy for budget-phone cameras. */
    const val MAX_QR_CHARS = 700
    const val MAX_PARTS = 99

    private val b64url = Base64.getUrlEncoder().withoutPadding()

    /** A piece of a multi-QR set. [index] is 1-based. */
    data class Part(val set: String, val index: Int, val count: Int, val chunk: String)

    /** Base64url of the binary event without its id: the payload of `harkara:e:` and of the web link `#e:`. */
    fun payload(event: Event): String = b64url.encodeToString(BinaryEventCodec.encode(event, includeId = false))

    /** One code if it fits [maxChars], else the multi-QR parts. */
    fun encode(event: Event, maxChars: Int = MAX_QR_CHARS): List<String> {
        val payload = payload(event)
        val single = EVENT_PREFIX + payload
        if (single.length <= maxChars) return listOf(single)
        val set = Hex.encode(Hashes.sha256(Base64.getUrlDecoder().decode(payload))).take(8)
        // Header "harkara:q:<8>:<i>/<n>:" with up to two digits each.
        val room = maxChars - (PART_PREFIX.length + 8 + 1 + 2 + 1 + 2 + 1)
        require(room >= 32) { "maxChars too small" }
        val chunks = payload.chunked(room)
        require(chunks.size <= MAX_PARTS) { "event too large for $MAX_PARTS codes" }
        return chunks.mapIndexed { i, chunk -> "$PART_PREFIX$set:${i + 1}/${chunks.size}:$chunk" }
    }

    /** Decodes a single `harkara:e:` code (or its bare payload); null when malformed or the signature fails. */
    fun decodeSingle(text: String): Event? = runCatching {
        val payload = text.trim().removePrefix(EVENT_PREFIX)
        BinaryEventCodec.decode(Base64.getUrlDecoder().decode(payload)).takeIf { it.validate() }
    }.getOrNull()

    fun parsePart(text: String): Part? {
        val t = text.trim()
        if (!t.startsWith(PART_PREFIX)) return null
        val fields = t.removePrefix(PART_PREFIX).split(':', limit = 3)
        if (fields.size != 3) return null
        val (set, position, chunk) = fields
        val (i, n) = position.split('/').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        if (!Hex.isLowerHex(set, 4) || n !in 1..MAX_PARTS || i !in 1..n || chunk.isEmpty()) return null
        if (!chunk.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return null
        return Part(set, i, n, chunk)
    }
}

/** Collects multi-QR parts (any order, duplicates fine) until a set is complete and checks out. */
class QrAssembler(private val nowMillis: () -> Long = System::currentTimeMillis, private val keepMillis: Long = 30 * 60_000L) {
    sealed interface Result {
        data class Progress(val set: String, val have: Int, val total: Int) : Result
        data class Complete(val event: Event) : Result
        data object Invalid : Result
    }

    private class Pending(val count: Int, val chunks: MutableMap<Int, String>, var touched: Long)

    private val sets = LinkedHashMap<String, Pending>()

    fun add(text: String): Result {
        QrTransport.decodeSingle(text)?.let { return Result.Complete(it) }
        val part = QrTransport.parsePart(text) ?: return Result.Invalid
        val now = nowMillis()
        sets.entries.removeAll { now - it.value.touched > keepMillis }
        val pending = sets.getOrPut(part.set) { Pending(part.count, HashMap(), now) }
        if (pending.count != part.count) return Result.Invalid
        pending.chunks[part.index] = part.chunk
        pending.touched = now
        if (pending.chunks.size < pending.count) return Result.Progress(part.set, pending.chunks.size, pending.count)
        sets.remove(part.set)
        val payload = (1..pending.count).joinToString("") { pending.chunks.getValue(it) }
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload) }.getOrNull() ?: return Result.Invalid
        if (Hex.encode(Hashes.sha256(bytes)).take(8) != part.set) return Result.Invalid
        val event = runCatching { BinaryEventCodec.decode(bytes) }.getOrNull()?.takeIf { it.validate() } ?: return Result.Invalid
        return Result.Complete(event)
    }

    /** Sets started but not finished. */
    val pendingSets: Int get() = sets.size
}

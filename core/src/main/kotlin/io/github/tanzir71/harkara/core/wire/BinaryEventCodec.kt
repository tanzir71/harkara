package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.EventId
import io.github.tanzir71.harkara.core.nostr.Hex
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compact binary form of a NIP-01 event for Bluetooth and SMS. It carries exactly the signed fields, so a
 * bridging phone re-encodes to JSON (or back) and never re-signs:
 *
 * ```
 * u8      version (1)
 * u8      flags   (0x01 content is raw DEFLATE, 0x02 id omitted: recompute on decode)
 * 32      id      (absent when flag 0x02)
 * 32      pubkey
 * 64      sig
 * u32 BE  created_at
 * varint  kind
 * varint  tag count, then per tag: varint item count, then per item: varint length + UTF-8
 * varint  content length + content bytes (UTF-8, or raw DEFLATE of the UTF-8 when smaller)
 * ```
 */
object BinaryEventCodec {
    const val VERSION: Int = 1
    const val FLAG_DEFLATE: Int = 0x01
    const val FLAG_NO_ID: Int = 0x02

    /** Decoded content is capped so a tiny compressed payload cannot expand into a huge one. */
    const val MAX_CONTENT_BYTES: Int = 64 * 1024
    private const val MAX_TAGS = 2_000
    private const val MAX_TAG_ITEMS = 100

    /**
     * Encodes [event]. With [includeId] false the 32-byte id is left out (SMS), and the decoder recomputes it.
     * Throws [IllegalArgumentException] when a field cannot be carried (created_at beyond u32, malformed hex,
     * or text that is not valid UTF-16 and so would not survive UTF-8).
     */
    fun encode(event: Event, includeId: Boolean = true): ByteArray {
        require(Hex.isLowerHex(event.id, 32) && Hex.isLowerHex(event.pubkey, 32) && Hex.isLowerHex(event.sig, 64)) { "malformed event hex" }
        require(event.createdAt in 0..0xFFFF_FFFFL) { "created_at does not fit in u32" }
        require(event.kind in 0..Event.MAX_KIND) { "kind out of range" }
        val raw = utf8(event.content)
        val deflated = deflate(raw)
        val compress = deflated.size < raw.size
        var flags = 0
        if (compress) flags = flags or FLAG_DEFLATE
        if (!includeId) flags = flags or FLAG_NO_ID
        val out = ByteArrayOutputStream(160 + raw.size)
        out.write(VERSION)
        out.write(flags)
        if (includeId) out.write(Hex.decode(event.id))
        out.write(Hex.decode(event.pubkey))
        out.write(Hex.decode(event.sig))
        val t = event.createdAt
        out.write((t ushr 24).toInt())
        out.write((t ushr 16).toInt())
        out.write((t ushr 8).toInt())
        out.write(t.toInt())
        Varint.write(out, event.kind.toLong())
        Varint.write(out, event.tags.size.toLong())
        event.tags.forEach { tag ->
            Varint.write(out, tag.size.toLong())
            tag.forEach { item -> writeBytes(out, utf8(item)) }
        }
        writeBytes(out, if (compress) deflated else raw)
        return out.toByteArray()
    }

    /** Decodes bytes from [encode]. Throws [IllegalArgumentException] on truncated or malformed input. */
    fun decode(bytes: ByteArray): Event {
        val r = Reader(bytes)
        val version = r.u8()
        require(version == VERSION) { "unsupported binary event version $version" }
        val flags = r.u8()
        require(flags and (FLAG_DEFLATE or FLAG_NO_ID).inv() == 0) { "unknown flags" }
        val id = if (flags and FLAG_NO_ID == 0) Hex.encode(r.bytes(32)) else null
        val pubkey = Hex.encode(r.bytes(32))
        val sig = Hex.encode(r.bytes(64))
        val createdAt = (r.u8().toLong() shl 24) or (r.u8().toLong() shl 16) or (r.u8().toLong() shl 8) or r.u8().toLong()
        val kind = r.varint()
        require(kind in 0..Event.MAX_KIND.toLong()) { "kind out of range" }
        val tagCount = r.varint()
        require(tagCount in 0..MAX_TAGS.toLong()) { "too many tags" }
        val tags = List(tagCount.toInt()) {
            val items = r.varint()
            require(items in 0..MAX_TAG_ITEMS.toLong()) { "too many tag items" }
            List(items.toInt()) { r.lengthPrefixed().decodeToString() }
        }
        val stored = r.lengthPrefixed()
        require(r.done()) { "trailing bytes after event" }
        val content = (if (flags and FLAG_DEFLATE != 0) inflate(stored) else stored).decodeToString()
        val kindInt = kind.toInt()
        return Event(
            id = id ?: EventId.compute(pubkey, createdAt, kindInt, tags, content),
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kindInt,
            tags = tags,
            content = content,
            sig = sig,
        )
    }

    private fun utf8(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        require(bytes.decodeToString() == text) { "text is not valid UTF-16 and cannot be carried as UTF-8" }
        return bytes
    }

    private fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
        Varint.write(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun deflate(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(1024)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            // A trailing dummy byte, as Inflater's nowrap mode documents.
            inflater.setInput(input + 0)
            val out = ByteArrayOutputStream(input.size * 3)
            val buffer = ByteArray(1024)
            var stalls = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    require(!inflater.needsInput() && !inflater.needsDictionary() && ++stalls < 4) { "truncated compressed content" }
                }
                out.write(buffer, 0, n)
                require(out.size() <= MAX_CONTENT_BYTES) { "content too large" }
            }
            out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            throw IllegalArgumentException("bad compressed content", e)
        } finally {
            inflater.end()
        }
    }
}

/** Unsigned LEB128 varints. */
object Varint {
    fun write(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "varints are unsigned" }
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun encode(value: Long): ByteArray = ByteArrayOutputStream(10).also { write(it, value) }.toByteArray()
}

/** Bounds-checked reader shared by the wire codecs. */
internal class Reader(private val bytes: ByteArray, private var pos: Int = 0) {
    fun u8(): Int {
        require(pos < bytes.size) { "truncated input" }
        return bytes[pos++].toInt() and 0xff
    }

    fun bytes(n: Int): ByteArray {
        require(n >= 0 && pos + n <= bytes.size) { "truncated input" }
        return bytes.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(shift < 63) { "varint too long" }
            val b = u8()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun lengthPrefixed(): ByteArray {
        val n = varint()
        require(n in 0..(bytes.size - pos).toLong()) { "length exceeds input" }
        return bytes(n.toInt())
    }

    fun remaining(): Int = bytes.size - pos

    fun done(): Boolean = pos == bytes.size
}

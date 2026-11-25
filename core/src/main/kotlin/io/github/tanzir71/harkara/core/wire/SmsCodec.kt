package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.EventId
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Schnorr
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Finds the full 32-byte values (lowercase hex) this phone already knows whose first 8 bytes are [prefix]:
 * public keys of contacts and parties, ids of events it holds, IOU hashes. Used to expand the 8-byte
 * references an SMS payload carries; the signature check decides between candidates.
 */
fun interface PrefixResolver {
    fun resolve(prefix: ByteArray): List<String>

    companion object {
        /** A resolver over a fixed set of known values. */
        fun of(values: Collection<String>): PrefixResolver {
            val byPrefix = values.filter { Hex.isLowerHex(it, 32) }.distinct().groupBy { it.substring(0, SmsCodec.REF_BYTES * 2) }
            return PrefixResolver { p -> byPrefix[Hex.encode(p)].orEmpty() }
        }
    }
}

/**
 * The SMS form of a signed event (handover §5.6): a short readable Latin line, the marker `HK1:` and a
 * Base64url payload, all in the GSM-7 alphabet so two joined parts carry 306 characters. The payload
 * carries exactly the signed fields; the receiver recomputes the id and checks the signature, so a phone
 * that bridges an SMS onto a relay re-encodes and never re-signs. Bangla text rides inside the payload only.
 *
 * ```
 * u8      0x10 | flags      version 1 (high nibble); 0x01 = text block is raw DEFLATE with DICTIONARY
 * varint  kind
 * 8       author reference: first 8 bytes of the x-only pubkey (resolved by the receiver)
 * u32 BE  created_at
 * 64      BIP-340 signature
 * varint  tag count; per tag: varint item count, then items
 * varint  content form: 0 = text (the end of the text block), 1 = base64 → varint length + raw bytes
 * …       text block (rest of the payload): Text8 of every text item in order, then the content
 * ```
 *
 * Item header = varint `(n shl 3) or type`: 0 TEXT (n Text8 bytes in the text block), 1 HEX (n raw bytes
 * of a lowercase hex string), 2 UUID (16 bytes, canonical lowercase 8-4-4-4-12), 3 REF (8-byte prefix of a
 * 32-byte value the receiver knows), 4 AUTHOR (the event's pubkey), 5 SAME (n-th 32-byte value seen
 * earlier in this payload), 6 JOIN (n parts joined with `:`), 7 DEC (n is a decimal number).
 *
 * Text8 keeps ASCII as one byte, maps the Bengali block U+0980–U+09FF onto 0x80–0xFF (one byte per Bangla
 * letter instead of three) and writes anything else as 0x7F followed by its UTF-8.
 */
object SmsCodec {
    const val MARKER = "HK1:"
    const val VERSION = 1
    const val FLAG_DEFLATE = 0x01
    const val REF_BYTES = 8

    /** Two joined GSM-7 parts. */
    const val MAX_PARTS = 2
    const val MAX_CHARS = MAX_PARTS * Gsm7.MULTI_PART_SEPTETS

    private const val T_TEXT = 0
    private const val T_HEX = 1
    private const val T_UUID = 2
    private const val T_REF = 3
    private const val T_AUTHOR = 4
    private const val T_SAME = 5
    private const val T_JOIN = 6
    private const val T_DEC = 7

    private const val C_TEXT = 0
    private const val C_BASE64 = 1

    private const val MAX_TEXT_BYTES = 8 * 1024
    private const val MAX_TAGS = 200
    private const val MAX_ITEMS = 50
    private const val MAX_PARTS_PER_JOIN = 16
    private const val MAX_HEX_BYTES = 1024
    private const val MAX_COMBINATIONS = 64

    /**
     * Preset DEFLATE dictionary: tag names and values Harkara's SMS-eligible events use. Part of the
     * version 1 format; never change it (a new dictionary needs a new version).
     */
    val DICTIONARY: ByteArray = (
        "expirationplacenamenotestatusfalse_alarmresolvedcashrocketreasoncodedeclinedeliveredpicked_up" +
            "accept30402stepiou{\"method\":\"nagad\",\"amount\":{\"method\":\"bkash\",\"amount\":,\"trxId\":\""
        ).encodeToByteArray()

    private val b64url = Base64.getUrlEncoder().withoutPadding()
    private val PAYLOAD = Regex("""HK1:([A-Za-z0-9_-]{8,})""")

    /** The readable Latin line in front of the payload (GSM-7 only, never Bangla). */
    fun readableLine(kind: Int): String = when (kind) {
        Kinds.CHECK_IN -> "Harkara check-in"
        Kinds.SOS -> "Harkara SOS"
        Kinds.SOS_UPDATE -> "Harkara SOS update"
        Kinds.IOU_SIGN -> "Harkara khata sign"
        Kinds.SETTLE -> "Harkara khata paid"
        Kinds.TASK_STEP -> "Harkara task"
        Kinds.SMS_DM -> "Harkara msg"
        else -> "Harkara"
    }

    /**
     * Encodes [event]. 32-byte values (keys, event ids, hashes) listed in [refs] travel as 8-byte references
     * the receiver must already know; everything else travels in full.
     */
    fun encode(event: Event, refs: Set<String> = emptySet()): ByteArray {
        require(Hex.isLowerHex(event.pubkey, 32) && Hex.isLowerHex(event.sig, 64)) { "malformed event hex" }
        require(event.createdAt in 0..0xFFFF_FFFFL) { "created_at does not fit in u32" }
        require(event.kind in 0..Event.MAX_KIND) { "kind out of range" }
        require(event.tags.size <= MAX_TAGS && event.tags.all { it.size <= MAX_ITEMS }) { "too many tags" }
        val w = ItemWriter(event.pubkey, refs)
        val struct = ByteArrayOutputStream()
        Varint.write(struct, event.tags.size.toLong())
        for (tag in event.tags) {
            Varint.write(struct, tag.size.toLong())
            tag.forEach { w.item(struct, it, allowJoin = true) }
        }
        val raw = base64Content(event.content)
        if (raw != null) {
            Varint.write(struct, C_BASE64.toLong())
            Varint.write(struct, raw.size.toLong())
            struct.write(raw)
        } else {
            Varint.write(struct, C_TEXT.toLong())
            w.text.write(Text8.encode(event.content))
        }
        val text = w.text.toByteArray()
        val deflated = if (text.isEmpty()) text else deflate(text)
        val compress = deflated.size < text.size
        val out = ByteArrayOutputStream(96 + text.size)
        out.write((VERSION shl 4) or (if (compress) FLAG_DEFLATE else 0))
        Varint.write(out, event.kind.toLong())
        out.write(Hex.decode(event.pubkey), 0, REF_BYTES)
        val t = event.createdAt
        out.write((t ushr 24).toInt())
        out.write((t ushr 16).toInt())
        out.write((t ushr 8).toInt())
        out.write(t.toInt())
        out.write(Hex.decode(event.sig))
        out.write(struct.toByteArray())
        out.write(if (compress) deflated else text)
        return out.toByteArray()
    }

    /**
     * Decodes [payload], resolving the author and every reference through [resolver], and returns the event
     * whose recomputed id the signature verifies. Throws [IllegalArgumentException] when the payload is
     * malformed or no known key/value makes the signature check.
     */
    fun decode(payload: ByteArray, resolver: PrefixResolver): Event {
        val r = Reader(payload)
        val head = r.u8()
        require(head ushr 4 == VERSION) { "unsupported SMS payload version ${head ushr 4}" }
        val flags = head and 0x0f
        require(flags and FLAG_DEFLATE.inv() == 0) { "unknown flags" }
        val kind = r.varint()
        require(kind in 0..Event.MAX_KIND.toLong()) { "kind out of range" }
        val authorRef = r.bytes(REF_BYTES)
        val createdAt = (r.u8().toLong() shl 24) or (r.u8().toLong() shl 16) or (r.u8().toLong() shl 8) or r.u8().toLong()
        val sig = r.bytes(64)

        val slots = mutableListOf<ByteArray>()
        val table = mutableListOf<Piece>()
        val tagCount = r.varint()
        require(tagCount in 0..MAX_TAGS.toLong()) { "too many tags" }
        val tags = List(tagCount.toInt()) {
            val items = r.varint()
            require(items in 0..MAX_ITEMS.toLong()) { "too many tag items" }
            List(items.toInt()) { readItem(r, slots, table, allowJoin = true) }
        }
        val contentForm = r.varint()
        val rawContent = when (contentForm) {
            C_TEXT.toLong() -> null
            C_BASE64.toLong() -> r.lengthPrefixed()
            else -> throw IllegalArgumentException("unknown content form")
        }
        val stored = r.bytes(r.remaining())
        val block = if (flags and FLAG_DEFLATE != 0) inflate(stored) else stored
        require(block.size <= MAX_TEXT_BYTES) { "text too large" }

        // Fill text pieces from the block, in the order they were written; the content is what is left.
        var pos = 0
        val filled = tags.map { tag ->
            tag.map { item ->
                item.map { piece ->
                    if (piece is Piece.Text) {
                        require(pos + piece.length <= block.size) { "text block too short" }
                        Piece.Lit(Text8.decode(block, pos, pos + piece.length)).also { pos += piece.length }
                    } else {
                        piece
                    }
                }
            }
        }
        val content = if (rawContent != null) {
            require(pos == block.size) { "trailing text" }
            Base64.getEncoder().encodeToString(rawContent)
        } else {
            Text8.decode(block, pos, block.size)
        }

        val authors = candidates(authorRef, resolver)
        val slotValues = slots.map { candidates(it, resolver) }
        require(authors.isNotEmpty()) { "unknown author key" }
        require(slotValues.all { it.isNotEmpty() }) { "unknown reference" }
        var combinations = authors.size.toLong()
        slotValues.forEach { combinations *= it.size }
        require(combinations <= MAX_COMBINATIONS) { "too many candidate keys" }

        val sigHex = Hex.encode(sig)
        for (author in authors) {
            for (choice in choices(slotValues)) {
                val resolved = filled.map { tag ->
                    tag.map { item ->
                        item.joinToString("") { piece ->
                            when (piece) {
                                is Piece.Lit -> piece.value
                                is Piece.Author -> author
                                is Piece.Slot -> choice[piece.index]
                                is Piece.Text -> error("unfilled text")
                            }
                        }
                    }
                }
                val id = EventId.computeBytes(author, createdAt, kind.toInt(), resolved, content)
                if (Schnorr.verify(sig, id, Hex.decode(author))) {
                    return Event(Hex.encode(id), author, createdAt, kind.toInt(), resolved, content, sigHex)
                }
            }
        }
        throw IllegalArgumentException("signature does not verify with any known key")
    }

    /** The whole SMS: readable line, a space, the marker and the payload. */
    fun text(event: Event, refs: Set<String> = emptySet()): String =
        readableLine(event.kind) + " " + MARKER + b64url.encodeToString(encode(event, refs))

    /** True when [text] is GSM-7 and fits [MAX_PARTS] joined parts. */
    fun fits(text: String): Boolean = Gsm7.isGsm7(text) && Gsm7.parts(text) <= MAX_PARTS

    /** The SMS text for [event] in full form if that fits two parts, else with [refs] shortened; null if neither fits. */
    fun textFitting(event: Event, refs: Set<String> = emptySet()): String? {
        val full = text(event)
        if (fits(full)) return full
        if (refs.isEmpty()) return null
        return text(event, refs).takeIf(::fits)
    }

    /** Finds an SMS payload in pasted or received text (the marker may sit anywhere in it). */
    fun extract(text: String): ByteArray? {
        val match = PAYLOAD.find(text) ?: return null
        return runCatching { Base64.getUrlDecoder().decode(match.groupValues[1]) }.getOrNull()
    }

    /** True when [text] contains an SMS payload marker. */
    fun looksLikeSms(text: String): Boolean = PAYLOAD.containsMatchIn(text)

    /** Decodes and verifies the event in [text], or null. */
    fun decodeText(text: String, resolver: PrefixResolver): Event? =
        extract(text)?.let { bytes -> runCatching { decode(bytes, resolver) }.getOrNull() }

    // ---------- items ----------

    private sealed interface Piece {
        data class Lit(val value: String) : Piece
        data class Text(val length: Int) : Piece
        data class Slot(val index: Int) : Piece
        data object Author : Piece
    }

    private class ItemWriter(val author: String, val refs: Set<String>) {
        val text = ByteArrayOutputStream()
        private val table = mutableListOf<String>()

        fun item(out: ByteArrayOutputStream, value: String, allowJoin: Boolean) {
            when {
                value == author -> header(out, 0, T_AUTHOR)
                Hex.isLowerHex(value, 32) && value in table -> header(out, table.indexOf(value).toLong(), T_SAME)
                Hex.isLowerHex(value, 32) && value in refs -> {
                    header(out, 0, T_REF)
                    out.write(Hex.decode(value), 0, REF_BYTES)
                    table += value
                }
                isHexRun(value) -> {
                    header(out, (value.length / 2).toLong(), T_HEX)
                    out.write(Hex.decode(value))
                    if (value.length == 64) table += value
                }
                isUuid(value) -> {
                    header(out, 0, T_UUID)
                    out.write(Hex.decode(value.replace("-", "")))
                }
                isDecimal(value) -> header(out, value.toLong(), T_DEC)
                allowJoin && ':' in value && value.split(':').let { parts -> parts.size <= MAX_PARTS_PER_JOIN && parts.any { special(it) } } -> {
                    val parts = value.split(':')
                    header(out, parts.size.toLong(), T_JOIN)
                    parts.forEach { item(out, it, allowJoin = false) }
                }
                else -> {
                    val bytes = Text8.encode(value)
                    header(out, bytes.size.toLong(), T_TEXT)
                    text.write(bytes)
                }
            }
        }

        private fun special(part: String) =
            part == author || Hex.isLowerHex(part, 32) || isHexRun(part) || isUuid(part) || isDecimal(part)

        private fun header(out: ByteArrayOutputStream, n: Long, type: Int) = Varint.write(out, (n shl 3) or type.toLong())
    }

    private fun readItem(r: Reader, slots: MutableList<ByteArray>, table: MutableList<Piece>, allowJoin: Boolean): List<Piece> {
        val h = r.varint()
        val n = h ushr 3
        return when ((h and 7).toInt()) {
            T_TEXT -> {
                require(n <= MAX_TEXT_BYTES) { "text item too long" }
                listOf(Piece.Text(n.toInt()))
            }
            T_HEX -> {
                require(n in 1..MAX_HEX_BYTES.toLong()) { "bad hex length" }
                val hex = Hex.encode(r.bytes(n.toInt()))
                if (n == 32L) table += Piece.Lit(hex)
                listOf(Piece.Lit(hex))
            }
            T_UUID -> {
                val hex = Hex.encode(r.bytes(16))
                listOf(Piece.Lit("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"))
            }
            T_REF -> {
                slots += r.bytes(REF_BYTES)
                val slot = Piece.Slot(slots.size - 1)
                table += slot
                listOf(slot)
            }
            T_AUTHOR -> listOf(Piece.Author)
            T_SAME -> {
                require(n < table.size) { "bad back-reference" }
                listOf(table[n.toInt()])
            }
            T_JOIN -> {
                require(allowJoin && n in 2..MAX_PARTS_PER_JOIN.toLong()) { "bad join" }
                buildList {
                    repeat(n.toInt()) { i ->
                        if (i > 0) add(Piece.Lit(":"))
                        addAll(readItem(r, slots, table, allowJoin = false))
                    }
                }
            }
            T_DEC -> listOf(Piece.Lit(n.toString()))
            else -> throw IllegalArgumentException("unknown item type")
        }
    }

    private fun candidates(prefix: ByteArray, resolver: PrefixResolver): List<String> {
        val p = Hex.encode(prefix)
        return resolver.resolve(prefix.copyOf()).filter { Hex.isLowerHex(it, 32) && it.startsWith(p) }.distinct()
    }

    /** Every combination of one candidate per slot. */
    private fun choices(slots: List<List<String>>): Sequence<List<String>> = sequence {
        if (slots.isEmpty()) {
            yield(emptyList())
            return@sequence
        }
        val idx = IntArray(slots.size)
        while (true) {
            yield(List(slots.size) { slots[it][idx[it]] })
            var i = slots.size - 1
            while (i >= 0) {
                idx[i]++
                if (idx[i] < slots[i].size) break
                idx[i] = 0
                i--
            }
            if (i < 0) return@sequence
        }
    }

    private fun isHexRun(s: String) = s.length >= 16 && s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun isUuid(s: String): Boolean {
        if (s.length != 36) return false
        for ((i, c) in s.withIndex()) {
            val dash = i == 8 || i == 13 || i == 18 || i == 23
            if (dash != (c == '-')) return false
            if (!dash && c !in '0'..'9' && c !in 'a'..'f') return false
        }
        return true
    }

    private fun isDecimal(s: String) = s.isNotEmpty() && s.length <= 18 && s.all { it in '0'..'9' } && (s == "0" || s[0] != '0')

    /** NIP-44 payloads and other standard base64 travel as raw bytes when that round-trips exactly. */
    private fun base64Content(content: String): ByteArray? {
        if (content.length < 24 || content.length % 4 != 0) return null
        if (!content.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }) return null
        val bytes = runCatching { Base64.getDecoder().decode(content) }.getOrNull() ?: return null
        return bytes.takeIf { Base64.getEncoder().encodeToString(it) == content }
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setDictionary(DICTIONARY)
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(512)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setDictionary(DICTIONARY)
            inflater.setInput(input + 0)
            val out = ByteArrayOutputStream(input.size * 3)
            val buffer = ByteArray(512)
            var stalls = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) require(!inflater.needsInput() && !inflater.needsDictionary() && ++stalls < 4) { "truncated compressed text" }
                out.write(buffer, 0, n)
                require(out.size() <= MAX_TEXT_BYTES) { "text too large" }
            }
            // Nothing may follow the compressed stream (only the dummy byte added above).
            require(inflater.remaining <= 1) { "trailing bytes after the compressed text" }
            out.toByteArray()
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("bad compressed text", e)
        } finally {
            inflater.end()
        }
    }
}

/**
 * Compact text for SMS payloads: ASCII (except DEL) as itself, the Bengali block U+0980–U+09FF as one byte
 * 0x80–0xFF, and any other code point as 0x7F followed by its UTF-8.
 */
object Text8 {
    private const val ESCAPE = 0x7F
    private const val BENGALI = 0x0980

    fun encode(text: String): ByteArray {
        require(text.encodeToByteArray().decodeToString() == text) { "text is not valid UTF-16" }
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp < ESCAPE -> out.write(cp)
                cp in BENGALI until BENGALI + 0x80 -> out.write(0x80 + cp - BENGALI)
                else -> {
                    out.write(ESCAPE)
                    out.write(String(Character.toChars(cp)).encodeToByteArray())
                }
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val b = bytes[i].toInt() and 0xff
            when {
                b < ESCAPE -> { out.append(b.toChar()); i++ }
                b >= 0x80 -> { out.append((BENGALI + b - 0x80).toChar()); i++ }
                else -> {
                    require(i + 1 < to) { "truncated escape" }
                    val lead = bytes[i + 1].toInt() and 0xff
                    val len = when {
                        lead < 0x80 -> 1
                        lead ushr 5 == 0x6 -> 2
                        lead ushr 4 == 0xE -> 3
                        lead ushr 3 == 0x1E -> 4
                        else -> throw IllegalArgumentException("bad escape")
                    }
                    require(i + 1 + len <= to) { "truncated escape" }
                    out.append(bytes.decodeToString(i + 1, i + 1 + len, throwOnInvalidSequence = true))
                    i += 1 + len
                }
            }
        }
        return out.toString()
    }
}

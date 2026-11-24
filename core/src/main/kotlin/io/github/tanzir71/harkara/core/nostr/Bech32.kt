package io.github.tanzir71.harkara.core.nostr

import java.io.ByteArrayOutputStream

/** A decoded `nprofile`: a pubkey plus relay hints (NIP-19). */
data class Nip19Profile(val pubkey: String, val relays: List<String> = emptyList())

/** A decoded `nevent`: an event id plus optional relays, author and kind (NIP-19). */
data class Nip19Event(val id: String, val relays: List<String> = emptyList(), val author: String? = null, val kind: Int? = null)

/**
 * Bech32 (BIP-173, not bech32m) and the NIP-19 entities: `npub`, `nsec`, `note`, `nprofile`, `nevent`, plus
 * NIP-49 `ncryptsec`. Strings are limited to 5000 characters (NIP-19), not BIP-173's 90, since `ncryptsec`
 * alone is 162.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val MAX_LENGTH = 5000
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** Encodes 8-bit [data] under human-readable part [hrp]. */
    fun encode(hrp: String, data: ByteArray): String {
        require(hrp.isNotEmpty() && hrp.all { it.code in 33..126 && !it.isUpperCase() }) { "invalid hrp" }
        val values = convertBits(data, 8, 5, pad = true)
        val checksum = createChecksum(hrp, values)
        val out = StringBuilder(hrp.length + 1 + values.size + 6)
        out.append(hrp).append('1')
        (values + checksum).forEach { out.append(CHARSET[it]) }
        return out.toString()
    }

    /** Decodes a bech32 string into its hrp and 8-bit data. Throws [IllegalArgumentException] on any error. */
    fun decode(value: String): Pair<String, ByteArray> {
        require(value.length <= MAX_LENGTH) { "bech32 string too long" }
        require(value == value.lowercase() || value == value.uppercase()) { "mixed-case bech32" }
        val s = value.lowercase()
        val sep = s.lastIndexOf('1')
        require(sep >= 1 && sep + 7 <= s.length) { "missing bech32 separator or checksum" }
        val hrp = s.substring(0, sep)
        require(hrp.all { it.code in 33..126 }) { "invalid hrp" }
        val values = IntArray(s.length - sep - 1) { i ->
            val v = CHARSET.indexOf(s[sep + 1 + i])
            require(v >= 0) { "invalid bech32 character" }
            v
        }
        require(polymod(hrpExpand(hrp) + values) == 1) { "invalid bech32 checksum" }
        val data = convertBits(values.copyOf(values.size - 6).map { it.toByte() }.toByteArray(), 5, 8, pad = false)
        return hrp to ByteArray(data.size) { data[it].toByte() }
    }

    // NIP-19 bare keys and ids

    fun encodeNpub(pubkeyHex: String): String = encode("npub", hex32(pubkeyHex))
    fun encodeNsec(privateKey: ByteArray): String = encode("nsec", privateKey.also { require(it.size == 32) })
    fun encodeNote(eventIdHex: String): String = encode("note", hex32(eventIdHex))

    fun decodeNpub(npub: String): String = decode32(npub, "npub").toHex()
    fun decodeNsec(nsec: String): ByteArray = decode32(nsec, "nsec")
    fun decodeNote(note: String): String = decode32(note, "note").toHex()

    // NIP-19 TLV entities

    fun encodeNprofile(profile: Nip19Profile): String {
        val tlv = ByteArrayOutputStream()
        tlv.writeTlv(0, hex32(profile.pubkey))
        profile.relays.forEach { tlv.writeTlv(1, it.encodeToByteArray()) }
        return encode("nprofile", tlv.toByteArray())
    }

    fun decodeNprofile(value: String): Nip19Profile {
        val (hrp, data) = decode(value)
        require(hrp == "nprofile") { "expected nprofile" }
        val tlv = readTlv(data)
        val pubkey = tlv.firstOrNull { it.first == 0 }?.second ?: throw IllegalArgumentException("nprofile without pubkey")
        require(pubkey.size == 32) { "nprofile pubkey must be 32 bytes" }
        return Nip19Profile(pubkey.toHex(), tlv.filter { it.first == 1 }.map { it.second.decodeToString() })
    }

    fun encodeNevent(event: Nip19Event): String {
        val tlv = ByteArrayOutputStream()
        tlv.writeTlv(0, hex32(event.id))
        event.relays.forEach { tlv.writeTlv(1, it.encodeToByteArray()) }
        event.author?.let { tlv.writeTlv(2, hex32(it)) }
        event.kind?.let { k -> tlv.writeTlv(3, byteArrayOf((k ushr 24).toByte(), (k ushr 16).toByte(), (k ushr 8).toByte(), k.toByte())) }
        return encode("nevent", tlv.toByteArray())
    }

    fun decodeNevent(value: String): Nip19Event {
        val (hrp, data) = decode(value)
        require(hrp == "nevent") { "expected nevent" }
        val tlv = readTlv(data)
        val id = tlv.firstOrNull { it.first == 0 }?.second ?: throw IllegalArgumentException("nevent without id")
        require(id.size == 32) { "nevent id must be 32 bytes" }
        val author = tlv.firstOrNull { it.first == 2 }?.second?.also { require(it.size == 32) }?.toHex()
        val kind = tlv.firstOrNull { it.first == 3 }?.second?.also { require(it.size == 4) }
            ?.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
        return Nip19Event(id.toHex(), tlv.filter { it.first == 1 }.map { it.second.decodeToString() }, author, kind)
    }

    private fun hex32(hex: String): ByteArray {
        require(Hex.isLowerHex(hex, 32)) { "expected 32 bytes of lowercase hex" }
        return Hex.decode(hex)
    }

    private fun decode32(value: String, expectedHrp: String): ByteArray {
        val (hrp, data) = decode(value)
        require(hrp == expectedHrp) { "expected $expectedHrp, got $hrp" }
        require(data.size == 32) { "$expectedHrp must hold 32 bytes" }
        return data
    }

    private fun ByteArrayOutputStream.writeTlv(type: Int, value: ByteArray) {
        require(value.size <= 255) { "TLV value too long" }
        write(type)
        write(value.size)
        write(value)
    }

    /** Unknown TLV types are kept in the list and ignored by callers, as NIP-19 asks. */
    private fun readTlv(data: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i < data.size) {
            require(i + 2 <= data.size) { "truncated TLV" }
            val type = data[i].toInt() and 0xff
            val len = data[i + 1].toInt() and 0xff
            require(i + 2 + len <= data.size) { "truncated TLV value" }
            out += type to data.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return out
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1) { i ->
            when {
                i < hrp.length -> hrp[i].code ushr 5
                i == hrp.length -> 0
                else -> hrp[i - hrp.length - 1].code and 31
            }
        }

    private fun createChecksum(hrp: String, values: IntArray): IntArray {
        val mod = polymod(hrpExpand(hrp) + values + IntArray(6)) xor 1
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val maxv = (1 shl to) - 1
        val out = ArrayList<Int>(data.size * from / to + 1)
        for (b in data) {
            val value = b.toInt() and 0xff
            require(value ushr from == 0) { "invalid data range" }
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc ushr bits) and maxv
            }
        }
        if (pad) {
            if (bits > 0) out += (acc shl (to - bits)) and maxv
        } else {
            require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "invalid bech32 padding" }
        }
        return out.toIntArray()
    }
}

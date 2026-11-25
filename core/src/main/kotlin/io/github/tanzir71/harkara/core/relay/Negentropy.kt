package io.github.tanzir71.harkara.core.relay

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Negentropy v1 range-based set reconciliation, as used by NIP-77 (`NEG-OPEN` / `NEG-MSG`).
 * Written from the protocol description at https://github.com/hoytech/negentropy (MIT): items are
 * (created_at, 32-byte id) sorted by time then id; messages carry ranges with Skip, Fingerprint or IdList
 * payloads. The initiator ends up with [haveIds] (we have, relay lacks) and [needIds] (relay has, we lack).
 */
class Negentropy(items: List<Item>, private val frameSizeLimit: Int = 0) {
    data class Item(val timestamp: Long, val id: ByteArray) : Comparable<Item> {
        init {
            require(id.size == ID_SIZE) { "ids are 32 bytes" }
        }

        override fun compareTo(other: Item): Int {
            if (timestamp != other.timestamp) return timestamp.compareTo(other.timestamp)
            return compareBytes(id, other.id)
        }

        override fun equals(other: Any?) = other is Item && timestamp == other.timestamp && id.contentEquals(other.id)
        override fun hashCode() = 31 * timestamp.hashCode() + id.contentHashCode()

        companion object {
            fun of(timestamp: Long, idHex: String) = Item(timestamp, hexToBytes(idHex))
        }
    }

    private data class Bound(val timestamp: Long, val prefix: ByteArray) {
        override fun equals(other: Any?) = other is Bound && timestamp == other.timestamp && prefix.contentEquals(other.prefix)
        override fun hashCode() = 31 * timestamp.hashCode() + prefix.contentHashCode()
    }

    private val sorted: List<Item> = items.sorted().distinct()
    private var isInitiator = false
    private var lastTimestampIn = 0L
    private var lastTimestampOut = 0L

    /** Ids we have and the other side lacks (initiator only). */
    val haveIds = linkedSetOf<String>()

    /** Ids the other side has and we lack (initiator only). */
    val needIds = linkedSetOf<String>()

    /** First message, hex-encoded for `NEG-OPEN`. */
    fun initiate(): String {
        check(!isInitiator) { "already initiated" }
        isInitiator = true
        val out = ByteArrayOutputStream()
        out.write(PROTOCOL_VERSION)
        lastTimestampOut = 0
        splitRange(0, sorted.size, Bound(MAX_TIMESTAMP, ByteArray(0)), out)
        return bytesToHex(out.toByteArray())
    }

    /**
     * Processes a message (hex). Returns the reply, or null when the initiator is done.
     * The responder always returns a reply.
     */
    fun reconcile(queryHex: String): String? {
        val query = Reader(hexToBytes(queryHex))
        lastTimestampIn = 0
        lastTimestampOut = 0
        val full = ByteArrayOutputStream()
        full.write(PROTOCOL_VERSION)
        val version = query.byte()
        require(version == PROTOCOL_VERSION) { "unsupported negentropy version 0x${version.toString(16)}" }

        var prevIndex = 0
        var prevBound = Bound(0, ByteArray(0))
        var skip = false

        while (!query.done) {
            val o = ByteArrayOutputStream()
            fun doSkip() {
                if (skip) {
                    skip = false
                    writeBound(o, prevBound)
                    writeVarInt(o, MODE_SKIP)
                }
            }
            val currBound = readBound(query)
            val mode = query.varInt()
            val lower = prevIndex
            var upper = findLowerBound(prevIndex, sorted.size, currBound)

            when (mode) {
                MODE_SKIP -> skip = true
                MODE_FINGERPRINT -> {
                    val theirs = query.bytes(FINGERPRINT_SIZE)
                    if (!theirs.contentEquals(fingerprint(lower, upper))) {
                        doSkip()
                        splitRange(lower, upper, currBound, o)
                    } else {
                        skip = true
                    }
                }
                MODE_ID_LIST -> {
                    val n = query.varInt().toInt()
                    val theirs = LinkedHashMap<String, Boolean>()
                    repeat(n) { theirs[bytesToHex(query.bytes(ID_SIZE))] = true }
                    if (isInitiator) {
                        skip = true
                        for (i in lower until upper) {
                            val k = bytesToHex(sorted[i].id)
                            if (theirs.remove(k) == null) haveIds += k
                        }
                        needIds += theirs.keys
                    } else {
                        doSkip()
                        var endBound = currBound
                        val ids = ByteArrayOutputStream()
                        var count = 0
                        for (i in lower until upper) {
                            if (exceeded(full.size() + ids.size())) {
                                // Shrink the range; the frame check below sends a fingerprint for the rest.
                                endBound = Bound(sorted[i].timestamp, sorted[i].id)
                                upper = i
                                break
                            }
                            ids.write(sorted[i].id)
                            count++
                        }
                        writeBound(o, endBound)
                        writeVarInt(o, MODE_ID_LIST)
                        writeVarInt(o, count.toLong())
                        o.write(ids.toByteArray())
                        full.write(o.toByteArray())
                        o.reset()
                    }
                }
                else -> throw IllegalArgumentException("unexpected mode $mode")
            }

            if (exceeded(full.size() + o.size())) {
                val remaining = fingerprint(upper, sorted.size)
                writeBound(full, Bound(MAX_TIMESTAMP, ByteArray(0)))
                writeVarInt(full, MODE_FINGERPRINT)
                full.write(remaining)
                break
            } else {
                full.write(o.toByteArray())
            }
            prevIndex = upper
            prevBound = currBound
        }
        val bytes = full.toByteArray()
        return if (isInitiator && bytes.size == 1) null else bytesToHex(bytes)
    }

    private fun exceeded(size: Int) = frameSizeLimit != 0 && size > frameSizeLimit - 200

    private fun splitRange(lower: Int, upper: Int, upperBound: Bound, o: ByteArrayOutputStream) {
        val n = upper - lower
        if (n < BUCKETS * 2) {
            writeBound(o, upperBound)
            writeVarInt(o, MODE_ID_LIST)
            writeVarInt(o, n.toLong())
            for (i in lower until upper) o.write(sorted[i].id)
            return
        }
        val perBucket = n / BUCKETS
        val withExtra = n % BUCKETS
        var curr = lower
        for (i in 0 until BUCKETS) {
            val size = perBucket + if (i < withExtra) 1 else 0
            val fp = fingerprint(curr, curr + size)
            curr += size
            val next = if (curr == upper) upperBound else minimalBound(sorted[curr - 1], sorted[curr])
            writeBound(o, next)
            writeVarInt(o, MODE_FINGERPRINT)
            o.write(fp)
        }
    }

    private fun minimalBound(prev: Item, curr: Item): Bound {
        if (curr.timestamp != prev.timestamp) return Bound(curr.timestamp, ByteArray(0))
        var shared = 0
        while (shared < ID_SIZE && curr.id[shared] == prev.id[shared]) shared++
        return Bound(curr.timestamp, curr.id.copyOf(shared + 1))
    }

    private fun findLowerBound(begin: Int, end: Int, bound: Bound): Int {
        var lo = begin
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareItemToBound(sorted[mid], bound) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compareItemToBound(item: Item, bound: Bound): Int {
        if (item.timestamp != bound.timestamp) return item.timestamp.compareTo(bound.timestamp)
        return compareBytes(item.id, bound.prefix)
    }

    /** SHA-256(sum of ids as 256-bit little-endian integers mod 2^256 ‖ varint(count)), first 16 bytes. */
    fun fingerprint(lower: Int, upper: Int): ByteArray {
        var acc = BigInteger.ZERO
        for (i in lower until upper) acc = acc.add(BigInteger(1, sorted[i].id.reversedArray()))
        acc = acc.mod(TWO_256)
        val le = acc.toByteArray().reversedArray().copyOf(ID_SIZE)
        val buf = ByteArrayOutputStream().apply {
            write(le)
            writeVarInt(this, (upper - lower).toLong())
        }
        return MessageDigest.getInstance("SHA-256").digest(buf.toByteArray()).copyOf(FINGERPRINT_SIZE)
    }

    private fun writeBound(o: ByteArrayOutputStream, b: Bound) {
        writeTimestamp(o, b.timestamp)
        writeVarInt(o, b.prefix.size.toLong())
        o.write(b.prefix)
    }

    private fun readBound(r: Reader): Bound {
        val ts = readTimestamp(r)
        val len = r.varInt().toInt()
        require(len <= ID_SIZE) { "bound prefix too long" }
        return Bound(ts, r.bytes(len))
    }

    private fun writeTimestamp(o: ByteArrayOutputStream, timestamp: Long) {
        if (timestamp == MAX_TIMESTAMP) {
            lastTimestampOut = MAX_TIMESTAMP
            writeVarInt(o, 0)
            return
        }
        val delta = timestamp - lastTimestampOut
        lastTimestampOut = timestamp
        writeVarInt(o, delta + 1)
    }

    private fun readTimestamp(r: Reader): Long {
        var t = r.varInt()
        t = if (t == 0L) MAX_TIMESTAMP else t - 1
        if (lastTimestampIn == MAX_TIMESTAMP || t == MAX_TIMESTAMP) {
            lastTimestampIn = MAX_TIMESTAMP
            return MAX_TIMESTAMP
        }
        t += lastTimestampIn
        lastTimestampIn = t
        return t
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        val done: Boolean get() = pos >= data.size

        fun byte(): Int {
            require(pos < data.size) { "truncated message" }
            return data[pos++].toInt() and 0xff
        }

        fun bytes(n: Int): ByteArray {
            require(pos + n <= data.size) { "truncated message" }
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun varInt(): Long {
            var res = 0L
            while (true) {
                val b = byte()
                res = (res shl 7) or (b and 0x7f).toLong()
                if (b and 0x80 == 0) return res
            }
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 0x61
        const val ID_SIZE = 32
        const val FINGERPRINT_SIZE = 16
        private const val BUCKETS = 16
        private const val MODE_SKIP = 0L
        private const val MODE_FINGERPRINT = 1L
        private const val MODE_ID_LIST = 2L
        const val MAX_TIMESTAMP = Long.MAX_VALUE
        private val TWO_256: BigInteger = BigInteger.ONE.shiftLeft(256)

        /** Base-128, most significant digit first, high bit set on all but the last byte. */
        fun writeVarInt(o: ByteArrayOutputStream, value: Long) {
            require(value >= 0)
            if (value == 0L) {
                o.write(0)
                return
            }
            val digits = ArrayList<Int>()
            var v = value
            while (v > 0) {
                digits += (v and 0x7f).toInt()
                v = v ushr 7
            }
            for (i in digits.indices.reversed()) o.write(if (i > 0) digits[i] or 0x80 else digits[i])
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a[i].toInt() and 0xff
                val y = b[i].toInt() and 0xff
                if (x != y) return x.compareTo(y)
            }
            return a.size.compareTo(b.size)
        }

        fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

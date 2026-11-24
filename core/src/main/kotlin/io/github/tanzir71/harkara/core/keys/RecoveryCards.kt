package io.github.tanzir71.harkara.core.keys

import io.github.tanzir71.harkara.core.nostr.Bech32
import java.security.SecureRandom

/**
 * Optional 2-of-3 recovery cards: Shamir secret sharing over GF(256) on the 32-byte root seed. Any two
 * cards rebuild the seed; one card alone reveals nothing about it. Cards are bech32 text (`hkcard1…`) so a
 * typo fails the checksum instead of restoring a wrong key.
 */
object RecoveryCards {
    const val THRESHOLD: Int = 2
    const val CARDS: Int = 3
    private const val HRP = "hkcard"
    private const val VERSION = 1

    /** One card: its x coordinate (1..3) and the 32 share bytes. */
    class Card(val index: Int, share: ByteArray) {
        private val bytes = share.copyOf()

        init {
            require(index in 1..255) { "card index must be 1..255" }
            require(bytes.size == KeyRing.SEED_BYTES) { "a card holds 32 bytes" }
        }

        val share: ByteArray
            get() = bytes.copyOf()

        fun encode(): String = Bech32.encode(HRP, byteArrayOf(VERSION.toByte(), THRESHOLD.toByte(), index.toByte()) + bytes)

        override fun equals(other: Any?): Boolean = other is Card && other.index == index && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * index + bytes.contentHashCode()

        companion object {
            fun decode(text: String): Card {
                val (hrp, data) = Bech32.decode(text.trim())
                require(hrp == HRP) { "not a Harkara recovery card" }
                require(data.size == 3 + KeyRing.SEED_BYTES) { "recovery card has the wrong length" }
                require(data[0].toInt() == VERSION && data[1].toInt() == THRESHOLD) { "unsupported recovery card" }
                return Card(data[2].toInt() and 0xff, data.copyOfRange(3, data.size))
            }
        }
    }

    /** Splits [seed] into three cards, any two of which rebuild it. */
    fun split(seed: ByteArray, random: SecureRandom = SecureRandom()): List<Card> {
        require(seed.size == KeyRing.SEED_BYTES) { "seed must be 32 bytes" }
        val slope = ByteArray(seed.size).also(random::nextBytes)
        return (1..CARDS).map { x ->
            Card(x, ByteArray(seed.size) { i -> (seed[i].toInt() xor Gf256.mul(slope[i].toInt() and 0xff, x)).toByte() })
        }.also { slope.fill(0) }
    }

    /** Rebuilds the seed from any two distinct cards. */
    fun combine(cards: List<Card>): ByteArray {
        val distinct = cards.distinctBy { it.index }
        require(distinct.size >= THRESHOLD) { "need two different cards" }
        val (a, b) = distinct
        val sa = a.share
        val sb = b.share
        // Lagrange at x = 0 for a line through (xa, ya), (xb, yb); in GF(2^8) subtraction is xor.
        val la = Gf256.div(b.index, a.index xor b.index)
        val lb = Gf256.div(a.index, a.index xor b.index)
        return ByteArray(sa.size) { i ->
            (Gf256.mul(sa[i].toInt() and 0xff, la) xor Gf256.mul(sb[i].toInt() and 0xff, lb)).toByte()
        }.also {
            sa.fill(0)
            sb.fill(0)
        }
    }
}

/** Arithmetic in GF(2^8) with the AES polynomial x^8 + x^4 + x^3 + x + 1. */
internal object Gf256 {
    private val exp = IntArray(510)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x xor (x shl 1) // multiply by the generator 3
            if (x and 0x100 != 0) x = x xor 0x11b
        }
        for (i in 255 until 510) exp[i] = exp[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun div(a: Int, b: Int): Int {
        require(b != 0) { "division by zero" }
        return if (a == 0) 0 else exp[log[a] + 255 - log[b]]
    }
}

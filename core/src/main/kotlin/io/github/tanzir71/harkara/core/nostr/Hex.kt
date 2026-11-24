package io.github.tanzir71.harkara.core.nostr

/** Lowercase hex, the only form Nostr uses for ids, keys and signatures. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    /** Decodes hex (either case). Throws [IllegalArgumentException] on odd length or non-hex characters. */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            ((nibble(hex[i * 2]) shl 4) or nibble(hex[i * 2 + 1])).toByte()
        }
    }

    /** True when [value] is exactly [bytes] bytes of lowercase hex. */
    fun isLowerHex(value: String, bytes: Int): Boolean =
        value.length == bytes * 2 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun nibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
}

internal fun ByteArray.toHex(): String = Hex.encode(this)

internal fun String.hexToBytes(): ByteArray = Hex.decode(this)

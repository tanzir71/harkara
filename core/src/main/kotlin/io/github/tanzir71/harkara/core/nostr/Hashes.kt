package io.github.tanzir71.harkara.core.nostr

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SHA-256, HMAC-SHA256 and HKDF-SHA256 (RFC 5869) on the platform providers. */
object Hashes {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty HMAC key is valid (RFC 2104) but SecretKeySpec rejects it; HKDF's default salt is HashLen zeros instead.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    /** HKDF-Extract: PRK = HMAC(salt, ikm). An empty salt means 32 zero bytes. */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    /** HKDF-Expand to [length] bytes (at most 255 * 32). */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "invalid HKDF length" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        return out
    }

    /** Constant-time comparison for MACs. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

internal val secureRandom: SecureRandom by lazy { SecureRandom() }

internal fun randomBytes(size: Int, random: SecureRandom = secureRandom): ByteArray =
    ByteArray(size).also(random::nextBytes)

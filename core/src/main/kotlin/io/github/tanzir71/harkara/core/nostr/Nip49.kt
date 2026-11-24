package io.github.tanzir71.harkara.core.nostr

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.text.Normalizer

/** Thrown when an `ncryptsec` cannot be decrypted (wrong password, damaged text, unsupported version). */
class Nip49Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * NIP-49 password-encrypted keys (`ncryptsec1…`): scrypt(NFKC password, 16-byte salt, 2^logN, r=8, p=1) →
 * XChaCha20-Poly1305 with the key-security byte as associated data. Harkara stores its 32-byte root seed in
 * the "private key" field, so one backup restores every derived key.
 */
object Nip49 {
    const val VERSION: Int = 0x02
    const val DEFAULT_LOG_N: Int = 16

    /** Key was handled insecurely (stored or pasted unencrypted). */
    const val KEY_INSECURE: Int = 0x00
    /** Key was never handled insecurely, as far as the app knows. */
    const val KEY_SECURE: Int = 0x01
    /** The app does not track this. */
    const val KEY_UNKNOWN: Int = 0x02

    private const val PAYLOAD_BYTES = 91

    fun encrypt(
        privateKey: ByteArray,
        password: String,
        logN: Int = DEFAULT_LOG_N,
        keySecurityByte: Int = KEY_UNKNOWN,
        random: SecureRandom = secureRandom,
    ): String {
        require(privateKey.size == 32) { "NIP-49 encrypts 32-byte keys" }
        require(logN in 1..22) { "logN out of range" }
        require(keySecurityByte in 0..2) { "key security byte must be 0, 1 or 2" }
        val salt = randomBytes(16, random)
        val nonce = randomBytes(24, random)
        val key = symmetricKey(password, salt, logN)
        val aad = byteArrayOf(keySecurityByte.toByte())
        val ciphertext = try {
            XChaCha20Poly1305.seal(key, nonce, privateKey, aad)
        } finally {
            key.fill(0)
        }
        val payload = byteArrayOf(VERSION.toByte(), logN.toByte()) + salt + nonce + aad + ciphertext
        check(payload.size == PAYLOAD_BYTES)
        return Bech32.encode("ncryptsec", payload)
    }

    /** Returns the 32-byte key. Throws [Nip49Exception] on a wrong password or damaged text. */
    fun decrypt(ncryptsec: String, password: String): ByteArray {
        val (hrp, payload) = try {
            Bech32.decode(ncryptsec.trim())
        } catch (e: IllegalArgumentException) {
            throw Nip49Exception("not a valid ncryptsec string", e)
        }
        if (hrp != "ncryptsec") throw Nip49Exception("not an ncryptsec string")
        if (payload.size != PAYLOAD_BYTES) throw Nip49Exception("ncryptsec has the wrong length")
        if (payload[0].toInt() != VERSION) throw Nip49Exception("unsupported ncryptsec version ${payload[0]}")
        val logN = payload[1].toInt() and 0xff
        if (logN !in 1..22) throw Nip49Exception("unsupported scrypt cost")
        val salt = payload.copyOfRange(2, 18)
        val nonce = payload.copyOfRange(18, 42)
        val aad = payload.copyOfRange(42, 43)
        val ciphertext = payload.copyOfRange(43, 91)
        val key = symmetricKey(password, salt, logN)
        return try {
            XChaCha20Poly1305.open(key, nonce, ciphertext, aad)
        } catch (e: InvalidCipherTextException) {
            throw Nip49Exception("wrong password or damaged backup", e)
        } finally {
            key.fill(0)
        }
    }

    /** The scrypt cost (log2 N) and key-security byte of an `ncryptsec`, without decrypting it. */
    fun inspect(ncryptsec: String): Pair<Int, Int> {
        val (hrp, payload) = Bech32.decode(ncryptsec.trim())
        require(hrp == "ncryptsec" && payload.size == PAYLOAD_BYTES) { "not an ncryptsec string" }
        return (payload[1].toInt() and 0xff) to (payload[42].toInt() and 0xff)
    }

    fun normalizePassword(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFKC)

    private fun symmetricKey(password: String, salt: ByteArray, logN: Int): ByteArray =
        SCrypt.generate(normalizePassword(password).encodeToByteArray(), salt, 1 shl logN, 8, 1, 32)
}

/** XChaCha20-Poly1305 built from HChaCha20 and BouncyCastle's RFC 8439 ChaCha20-Poly1305. */
internal object XChaCha20Poly1305 {
    fun seal(key: ByteArray, nonce24: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        run(true, key, nonce24, plaintext, aad)

    fun open(key: ByteArray, nonce24: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        run(false, key, nonce24, ciphertext, aad)

    private fun run(encrypt: Boolean, key: ByteArray, nonce24: ByteArray, input: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce24.size == 24) { "XChaCha20 needs a 32-byte key and 24-byte nonce" }
        val subkey = hChaCha20(key, nonce24.copyOfRange(0, 16))
        val nonce12 = ByteArray(12).also { nonce24.copyInto(it, 4, 16, 24) }
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(subkey), 128, nonce12, aad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        cipher.doFinal(out, n)
        subkey.fill(0)
        return out
    }

    /** HChaCha20 (draft-irtf-cfrg-xchacha): 20 ChaCha rounds over key + 16-byte nonce, output words 0-3 and 12-15. */
    fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32 && nonce16.size == 16)
        val s = IntArray(16)
        s[0] = 0x61707865
        s[1] = 0x3320646e
        s[2] = 0x79622d32
        s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) s[12 + i] = le32(nonce16, i * 4)
        repeat(10) {
            quarter(s, 0, 4, 8, 12)
            quarter(s, 1, 5, 9, 13)
            quarter(s, 2, 6, 10, 14)
            quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15)
            quarter(s, 1, 6, 11, 12)
            quarter(s, 2, 7, 8, 13)
            quarter(s, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        intArrayOf(s[0], s[1], s[2], s[3], s[12], s[13], s[14], s[15]).forEachIndexed { i, w ->
            out[i * 4] = w.toByte()
            out[i * 4 + 1] = (w ushr 8).toByte()
            out[i * 4 + 2] = (w ushr 16).toByte()
            out[i * 4 + 3] = (w ushr 24).toByte()
        }
        s.fill(0)
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}

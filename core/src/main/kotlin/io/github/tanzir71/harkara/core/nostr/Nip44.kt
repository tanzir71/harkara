package io.github.tanzir71.harkara.core.nostr

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.util.Base64

/** Thrown when a NIP-44 payload or key is invalid. The message never contains secrets. */
class Nip44Exception(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * NIP-44 version 2 encryption: secp256k1 ECDH → HKDF-SHA256 conversation key → per-message HKDF keys →
 * ChaCha20 (RFC 8439, counter 0) over a padded plaintext → HMAC-SHA256 with the nonce as AAD.
 * Checked against the official vectors from paulmillr/nip44.
 */
object Nip44 {
    const val VERSION: Int = 2
    const val MIN_PLAINTEXT_SIZE: Int = 1
    const val MAX_PLAINTEXT_SIZE: Int = 65_535
    private val SALT = "nip44-v2".encodeToByteArray()

    /** Per-message keys derived from the conversation key and nonce. */
    class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    /** Conversation key shared by [privateKey]'s owner and [publicKey]'s owner (32-byte x-only key). */
    fun conversationKey(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val sharedX = try {
            Schnorr.sharedX(privateKey, publicKey)
        } catch (e: IllegalArgumentException) {
            throw Nip44Exception("invalid key for conversation key", e)
        }
        return Hashes.hkdfExtract(SALT, sharedX)
    }

    fun conversationKey(privateKey: ByteArray, publicKeyHex: String): ByteArray {
        if (!Hex.isLowerHex(publicKeyHex.lowercase(), 32)) throw Nip44Exception("public key must be 32 bytes of hex")
        return conversationKey(privateKey, Hex.decode(publicKeyHex))
    }

    fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        if (conversationKey.size != 32) throw Nip44Exception("conversation key must be 32 bytes")
        if (nonce.size != 32) throw Nip44Exception("nonce must be 32 bytes")
        val keys = Hashes.hkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 0) throw Nip44Exception("expected a positive length")
        if (unpaddedLen <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(unpaddedLen - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    /** Encrypts UTF-8 [plaintext] (1..65535 bytes). [nonce] is random unless given (tests only). */
    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray? = null): String {
        val n = nonce ?: randomBytes(32)
        val keys = messageKeys(conversationKey, n)
        val padded = pad(plaintext)
        val ciphertext = chacha20(keys.chachaKey, keys.chachaNonce, padded)
        val mac = Hashes.hmacSha256(keys.hmacKey, n, ciphertext)
        val out = ByteArray(1 + 32 + ciphertext.size + 32)
        out[0] = VERSION.toByte()
        n.copyInto(out, 1)
        ciphertext.copyInto(out, 33)
        mac.copyInto(out, 33 + ciphertext.size)
        return Base64.getEncoder().encodeToString(out)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        if (payload.isEmpty() || payload[0] == '#') throw Nip44Exception("unknown encryption version")
        if (payload.length < 132 || payload.length > 87_472) throw Nip44Exception("invalid payload size")
        val data = try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw Nip44Exception("invalid base64", e)
        }
        if (data.size < 99 || data.size > 65_603) throw Nip44Exception("invalid data size")
        if (data[0].toInt() != VERSION) throw Nip44Exception("unknown encryption version ${data[0]}")
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val keys = messageKeys(conversationKey, nonce)
        val expected = Hashes.hmacSha256(keys.hmacKey, nonce, ciphertext)
        if (!Hashes.constantTimeEquals(expected, mac)) throw Nip44Exception("invalid MAC")
        return unpad(chacha20(keys.chachaKey, keys.chachaNonce, ciphertext))
    }

    private fun pad(plaintext: String): ByteArray {
        val unpadded = plaintext.encodeToByteArray()
        val len = unpadded.size
        if (len < MIN_PLAINTEXT_SIZE || len > MAX_PLAINTEXT_SIZE) throw Nip44Exception("invalid plaintext size: must be between 1 and 65535 bytes")
        val out = ByteArray(2 + calcPaddedLen(len))
        out[0] = (len ushr 8).toByte()
        out[1] = len.toByte()
        unpadded.copyInto(out, 2)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        if (padded.size < 2) throw Nip44Exception("invalid padding")
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (len == 0 || 2 + len > padded.size || padded.size != 2 + calcPaddedLen(len)) throw Nip44Exception("invalid padding")
        return padded.copyOfRange(2, 2 + len).decodeToString()
    }

    private fun chacha20(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(input.size)
        engine.processBytes(input, 0, input.size, out, 0)
        return out
    }
}

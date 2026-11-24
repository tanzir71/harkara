package io.github.tanzir71.harkara.core.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/**
 * A secp256k1 key pair. Public keys are 32-byte x-only keys (BIP-340), as used by Nostr.
 * Signing and verification go through ACINQ's secp256k1-kmp (libsecp256k1 over JNI).
 */
class KeyPair(privateKey: ByteArray) {
    private val secret: ByteArray = privateKey.copyOf()

    init {
        require(isValidPrivateKey(secret)) { "private key must be a 32-byte scalar in [1, n-1]" }
    }

    /** A copy of the 32-byte private key. */
    val privateKey: ByteArray
        get() = secret.copyOf()

    /** The 32-byte x-only public key. */
    val pubkey: ByteArray = Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33)

    val pubkeyHex: String = pubkey.toHex()

    /** BIP-340 signature over a 32-byte message (an event id), with fresh auxiliary randomness. */
    fun sign(messageHash32: ByteArray, auxRand: ByteArray? = randomBytes(32)): ByteArray {
        require(messageHash32.size == 32) { "BIP-340 signs 32-byte messages" }
        return Secp256k1.signSchnorr(messageHash32, secret, auxRand)
    }

    /** Overwrites the private key bytes held by this object. */
    fun wipe() = secret.fill(0)

    override fun equals(other: Any?): Boolean = other is KeyPair && other.pubkeyHex == pubkeyHex

    override fun hashCode(): Int = pubkeyHex.hashCode()

    override fun toString(): String = "KeyPair(pubkey=$pubkeyHex)"

    companion object {
        fun generate(random: SecureRandom = secureRandom): KeyPair {
            while (true) {
                val candidate = randomBytes(32, random)
                if (isValidPrivateKey(candidate)) return KeyPair(candidate)
            }
        }

        fun fromHex(privateKeyHex: String): KeyPair = KeyPair(Hex.decode(privateKeyHex))

        fun isValidPrivateKey(bytes: ByteArray): Boolean =
            bytes.size == 32 && runCatching { Secp256k1.secKeyVerify(bytes) }.getOrDefault(false)
    }
}

/** BIP-340 Schnorr verification. */
object Schnorr {
    /** False for any malformed input (wrong sizes, pubkey not on the curve) as well as a bad signature. */
    fun verify(signature: ByteArray, messageHash32: ByteArray, xOnlyPubkey: ByteArray): Boolean {
        if (signature.size != 64 || messageHash32.size != 32 || xOnlyPubkey.size != 32) return false
        return runCatching { Secp256k1.verifySchnorr(signature, messageHash32, xOnlyPubkey) }.getOrDefault(false)
    }

    fun verify(signatureHex: String, messageHash32: ByteArray, pubkeyHex: String): Boolean =
        Hex.isLowerHex(signatureHex, 64) && Hex.isLowerHex(pubkeyHex, 32) &&
            verify(signatureHex.hexToBytes(), messageHash32, pubkeyHex.hexToBytes())

    /** True when [pubkeyHex] is a valid x-only key (some point on the curve has this x). */
    fun isValidPubkey(pubkeyHex: String): Boolean =
        Hex.isLowerHex(pubkeyHex, 32) && runCatching { Secp256k1.pubkeyParse(byteArrayOf(2) + pubkeyHex.hexToBytes()) }.isSuccess

    /**
     * Unhashed ECDH: the 32-byte x coordinate of `privateKey * lift_x(xOnlyPubkey)`, as NIP-44 requires.
     * Throws [IllegalArgumentException] for an invalid private key or a pubkey that is not on the curve.
     */
    internal fun sharedX(privateKey: ByteArray, xOnlyPubkey: ByteArray): ByteArray {
        require(KeyPair.isValidPrivateKey(privateKey)) { "invalid private key" }
        require(xOnlyPubkey.size == 32) { "public key must be 32 bytes" }
        val point = try {
            Secp256k1.pubkeyParse(byteArrayOf(2) + xOnlyPubkey)
        } catch (e: Exception) {
            throw IllegalArgumentException("public key is not on the curve", e)
        }
        val shared = try {
            Secp256k1.pubKeyTweakMul(point, privateKey)
        } catch (e: Exception) {
            throw IllegalArgumentException("ECDH failed", e)
        }
        return shared.copyOfRange(1, 33)
    }
}

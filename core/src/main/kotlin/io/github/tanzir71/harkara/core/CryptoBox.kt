package io.github.tanzir71.harkara.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Long-term signing and key-agreement material for one Harkara identity. */
data class IdentityKeyPair(
    /** Public NearID derived from the Ed25519 public key. */
    val nearId: NearId,
    /** Encoded Ed25519 public key. */
    val ed25519PublicEncoded: ByteArray,
    /** Encoded X25519 public key. */
    val x25519PublicEncoded: ByteArray,
    /** Encoded Ed25519 private key. */
    val ed25519PrivateEncoded: ByteArray,
    /** Encoded X25519 private key. */
    val x25519PrivateEncoded: ByteArray,
)

/** Encrypted direct-message payload. */
data class DirectMessageEnvelope(
    /** Encoded X25519 sender public key used for ECDH. */
    val senderX25519PublicEncoded: ByteArray,
    /** AES-GCM nonce. */
    val nonce: ByteArray,
    /** Ciphertext plus authentication tag. */
    val ciphertext: ByteArray,
)

/** Cryptographic identity, signing, and direct-message helpers. */
object CryptoBox {
    /** Generates a fresh identity using the provided random source. */
    fun generateIdentity(random: SecureRandom = SecureRandom()): IdentityKeyPair {
        val signing = KeyPairGenerator.getInstance("Ed25519").apply {
            initialize(NamedParameterSpec("Ed25519"), random)
        }.generateKeyPair()
        val agreement = KeyPairGenerator.getInstance("X25519").apply {
            initialize(NamedParameterSpec("X25519"), random)
        }.generateKeyPair()
        return IdentityKeyPair(
            nearId = NearId.fromPublicKey(signing.public.encoded),
            ed25519PublicEncoded = signing.public.encoded,
            x25519PublicEncoded = agreement.public.encoded,
            ed25519PrivateEncoded = signing.private.encoded,
            x25519PrivateEncoded = agreement.private.encoded,
        )
    }

    /** Signs a public packet and returns a copy with the signed flag and signature set. */
    fun sign(packet: MeshPacket, identity: IdentityKeyPair): MeshPacket {
        val signable = packet.copy(flags = packet.flags and PacketFlags.SIGNED.inv(), signature = null)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(ed25519KeyPair(identity).private)
        signer.update(PacketCodec.signingBytes(signable))
        return signable.copy(flags = signable.flags or PacketFlags.SIGNED, signature = signer.sign())
    }

    /** Verifies a signed public packet against an encoded Ed25519 public key. */
    fun verify(packet: MeshPacket, ed25519PublicEncoded: ByteArray): Boolean {
        val signature = packet.signature ?: return false
        if (packet.flags and PacketFlags.SIGNED == 0) return false
        return runCatching {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(ed25519PublicKey(ed25519PublicEncoded))
            verifier.update(PacketCodec.signingBytes(packet))
            verifier.verify(signature)
        }.getOrDefault(false)
    }

    /** Encrypts a direct message for the holder of [recipientX25519Public]. */
    fun encryptDm(
        plaintext: ByteArray,
        sender: IdentityKeyPair,
        recipientX25519Public: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): DirectMessageEnvelope {
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val sharedSecret = sharedSecret(x25519KeyPair(sender).private, x25519PublicKey(recipientX25519Public))
        val key = hkdfSha256(sharedSecret, info = "Harkara-dm-v1".encodeToByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return DirectMessageEnvelope(
            senderX25519PublicEncoded = sender.x25519PublicEncoded,
            nonce = nonce,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    /** Decrypts a direct-message envelope for [recipient]. */
    fun decryptDm(envelope: DirectMessageEnvelope, recipient: IdentityKeyPair): ByteArray {
        return try {
            val sharedSecret = sharedSecret(
                x25519KeyPair(recipient).private,
                x25519PublicKey(envelope.senderX25519PublicEncoded),
            )
            val key = hkdfSha256(sharedSecret, info = "Harkara-dm-v1".encodeToByteArray())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, envelope.nonce),
            )
            cipher.doFinal(envelope.ciphertext)
        } catch (exception: Exception) {
            throw SecurityException("DM envelope authentication failed", exception)
        }
    }

    private fun ed25519KeyPair(identity: IdentityKeyPair): KeyPair =
        KeyPair(ed25519PublicKey(identity.ed25519PublicEncoded), ed25519PrivateKey(identity.ed25519PrivateEncoded))

    private fun x25519KeyPair(identity: IdentityKeyPair): KeyPair =
        KeyPair(x25519PublicKey(identity.x25519PublicEncoded), x25519PrivateKey(identity.x25519PrivateEncoded))

    private fun ed25519PublicKey(encoded: ByteArray) =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))

    private fun ed25519PrivateKey(encoded: ByteArray) =
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(encoded))

    private fun x25519PublicKey(encoded: ByteArray) =
        KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(encoded))

    private fun x25519PrivateKey(encoded: ByteArray) =
        KeyFactory.getInstance("X25519").generatePrivate(PKCS8EncodedKeySpec(encoded))

    private fun sharedSecret(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun hkdfSha256(inputKeyMaterial: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val salt = ByteArray(32)
        val pseudorandomKey = hmacSha256(salt, inputKeyMaterial)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copied = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, copied)
            offset += copied
            counter += 1
        }
        return output
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
}

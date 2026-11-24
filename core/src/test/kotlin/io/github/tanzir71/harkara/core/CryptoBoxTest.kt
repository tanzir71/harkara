package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant

class CryptoBoxTest {
    @Test
    fun `generated identity derives stable base32 NearID`() {
        val identity = CryptoBox.generateIdentity(deterministicRandom(1))

        assertEquals(16, identity.nearId.bytes.size)
        assertEquals(26, identity.nearId.toBase32().length)
        assertEquals(identity.nearId, NearId.fromPublicKey(identity.ed25519PublicEncoded))
    }

    @Test
    fun `sign and verify packet rejects tampered payload`() {
        val identity = CryptoBox.generateIdentity(deterministicRandom(2))
        val packet = MeshPacket(
            version = 1,
            type = PacketType.POST,
            ttl = 7,
            flags = PacketFlags.NONE,
            msgId = 42,
            senderId = identity.nearId,
            timestamp = Instant.ofEpochMilli(3_000),
            payload = "signed post".encodeToByteArray(),
        )

        val signed = CryptoBox.sign(packet, identity)
        val tampered = signed.copy(payload = "signed pest".encodeToByteArray())

        assertEquals(PacketFlags.SIGNED, signed.flags and PacketFlags.SIGNED)
        assertTrue(CryptoBox.verify(signed, identity.ed25519PublicEncoded))
        assertFalse(CryptoBox.verify(tampered, identity.ed25519PublicEncoded))
    }

    @Test
    fun `dm envelope decrypts for intended recipient`() {
        val alice = CryptoBox.generateIdentity(deterministicRandom(3))
        val bob = CryptoBox.generateIdentity(deterministicRandom(4))
        val plaintext = "meet near the clinic".encodeToByteArray()

        val envelope = CryptoBox.encryptDm(plaintext, sender = alice, recipientX25519Public = bob.x25519PublicEncoded)
        val decrypted = CryptoBox.decryptDm(envelope, recipient = bob)

        assertArrayEquals(plaintext, decrypted)
        assertNotEquals(plaintext.toList(), envelope.ciphertext.toList())
    }

    @Test
    fun `dm envelope rejects ciphertext tampering`() {
        val alice = CryptoBox.generateIdentity(deterministicRandom(5))
        val bob = CryptoBox.generateIdentity(deterministicRandom(6))
        val envelope = CryptoBox.encryptDm("private".encodeToByteArray(), alice, bob.x25519PublicEncoded)
        val tampered = envelope.copy(ciphertext = envelope.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })

        assertThrows(SecurityException::class.java) {
            CryptoBox.decryptDm(tampered, bob)
        }
    }

    private fun deterministicRandom(seed: Int): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(seed.toByte())) }
}

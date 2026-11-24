package io.github.tanzir71.harkara.core.nostr

import io.github.tanzir71.harkara.core.TestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Official BIP-340 vectors (bitcoin/bips bip-0340/test-vectors.csv, BSD-2-Clause). Nostr only signs 32-byte
 * event ids and libsecp256k1's JNI binding only takes 32-byte messages, so the four variable-length message
 * vectors (15-18, added 2022-12) are counted and skipped.
 */
class Bip340VectorsTest {
    private data class Vector(
        val index: Int,
        val secretKey: String,
        val publicKey: String,
        val auxRand: String,
        val message: String,
        val signature: String,
        val valid: Boolean,
        val comment: String,
    )

    private val vectors = TestData.resource("bip340-test-vectors.csv").lines().drop(1).filter { it.isNotBlank() }.map { line ->
        val c = line.split(',')
        Vector(c[0].toInt(), c[1], c[2], c[3], c[4], c[5], c[6] == "TRUE", c.drop(7).joinToString(","))
    }

    @Test
    fun `verification matches every 32-byte message vector`() {
        val thirtyTwo = vectors.filter { it.message.length == 64 }
        assertEquals(15, thirtyTwo.size)
        assertEquals(4, vectors.size - thirtyTwo.size, "variable-length message vectors skipped")
        thirtyTwo.forEach { v ->
            val ok = Schnorr.verify(Hex.decode(v.signature), Hex.decode(v.message), Hex.decode(v.publicKey))
            assertEquals(v.valid, ok, "vector ${v.index}: ${v.comment}")
        }
    }

    @Test
    fun `signing with the given aux rand reproduces the vector signatures`() {
        val signable = vectors.filter { it.secretKey.isNotEmpty() && it.message.length == 64 }
        assertEquals(4, signable.size)
        signable.forEach { v ->
            val keys = KeyPair(Hex.decode(v.secretKey))
            assertEquals(v.publicKey.lowercase(), keys.pubkeyHex, "pubkey ${v.index}")
            val sig = keys.sign(Hex.decode(v.message), Hex.decode(v.auxRand))
            assertEquals(v.signature.lowercase(), Hex.encode(sig), "signature ${v.index}")
            assertTrue(Schnorr.verify(sig, Hex.decode(v.message), keys.pubkey))
        }
    }

    @Test
    fun `invalid private keys are refused`() {
        assertEquals(false, KeyPair.isValidPrivateKey(ByteArray(32)))
        assertEquals(false, KeyPair.isValidPrivateKey(Hex.decode("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")))
        assertEquals(true, KeyPair.isValidPrivateKey(Hex.decode("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140")))
        assertEquals(false, KeyPair.isValidPrivateKey(ByteArray(31) { 1 }))
    }
}

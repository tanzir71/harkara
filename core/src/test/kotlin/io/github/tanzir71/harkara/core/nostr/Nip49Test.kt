package io.github.tanzir71.harkara.core.nostr

import io.github.tanzir71.harkara.core.TestData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Nip49Test {
    @Test
    fun `decrypts the NIP-49 spec vector`() {
        val ncryptsec = "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"

        val key = Nip49.decrypt(ncryptsec, "nostr")

        assertEquals("3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683", Hex.encode(key))
        assertEquals(16 to Nip49.KEY_INSECURE, Nip49.inspect(ncryptsec))
    }

    @Test
    fun `passwords are NFKC normalised`() {
        val input = "\u212B\u2126\u1E9B\u0323"
        val normalised = Nip49.normalizePassword(input)
        assertEquals("\u00C5\u03A9\u1E69", normalised)
        assertArrayEquals(
            byteArrayOf(0xC3.toByte(), 0x85.toByte(), 0xCE.toByte(), 0xA9.toByte(), 0xE1.toByte(), 0xB9.toByte(), 0xA9.toByte()),
            normalised.encodeToByteArray(),
        )
    }

    @Test
    fun `round trip, wrong password and tampering`() {
        val seed = ByteArray(32) { (it * 7 + 3).toByte() }

        val backup = Nip49.encrypt(seed, "\u212Bmar kotha", logN = 12, keySecurityByte = Nip49.KEY_SECURE)

        assertTrue(backup.startsWith("ncryptsec1"))
        assertEquals(162, backup.length)
        assertArrayEquals(seed, Nip49.decrypt(backup, "\u00C5mar kotha"))
        assertEquals(12 to Nip49.KEY_SECURE, Nip49.inspect(backup))
        assertThrows(Nip49Exception::class.java) { Nip49.decrypt(backup, "amar kotha") }
        val flipped = backup.substring(0, 60) + (if (backup[60] == 'q') 'p' else 'q') + backup.substring(61)
        assertThrows(Nip49Exception::class.java) { Nip49.decrypt(flipped, "\u00C5mar kotha") }
        assertThrows(Nip49Exception::class.java) { Nip49.decrypt("npub1xyz", "x") }
    }

    @Test
    fun `fresh salt and nonce every time`() {
        val key = TestData.keys(4).privateKey
        assertNotEquals(Nip49.encrypt(key, "pw", logN = 10), Nip49.encrypt(key, "pw", logN = 10))
    }

    @Test
    fun `hchacha20 matches the XChaCha draft vector`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = Hex.decode("000000090000004a0000000031415927")
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            Hex.encode(XChaCha20Poly1305.hChaCha20(key, nonce)),
        )
    }
}

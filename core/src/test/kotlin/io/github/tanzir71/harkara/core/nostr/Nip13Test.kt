package io.github.tanzir71.harkara.core.nostr

import io.github.tanzir71.harkara.core.TestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Nip13Test {
    @Test
    fun `difficulty counts leading zero bits`() {
        assertEquals(36, Nip13.difficulty("000000000e9d97a1ab09fc381030b346cdd7a142ad57e6df0b46dc9bef6c7e2d"))
        assertEquals(10, Nip13.difficulty("002f" + "f".repeat(60)))
        assertEquals(21, Nip13.difficulty("000006d8c378af1779d2feebc7603a125d99eca0ccf1085959b307f64e5dd358"))
        assertEquals(0, Nip13.difficulty("f" + "0".repeat(63)))
        assertEquals(256, Nip13.difficulty("0".repeat(64)))
    }

    @Test
    fun `the NIP-13 example meets its committed 20 bits`() {
        val note = Event.fromJson(NipExamples.NIP13_NOTE)
        assertEquals(20, Nip13.committedTarget(note))
        assertTrue(Nip13.meets(note, 20))
        assertFalse(Nip13.meets(note, 21), "committed target 20 is below 21")
    }

    @Test
    fun `mining 8 bits gives a signed event that verifies`() {
        val keys = TestData.keys(6)
        val template = UnsignedEvent(keys.pubkeyHex, TestData.NOW, Kinds.FEED_POST, listOf(listOf("g", "wh0r0d"), listOf("nonce", "0", "1")), "Octane at the Satmasjid Road pump")

        val mined = Nip13.mine(template, 8)
        val event = mined.sign(keys)

        assertTrue(Nip13.difficulty(event.id) >= 8)
        assertEquals("8", event.tags.last()[2])
        assertEquals(1, event.tags.count { it[0] == "nonce" })
        assertEquals(listOf("g", "wh0r0d"), event.tags.first())
        assertTrue(event.validate())
        assertTrue(Nip13.meets(event, 8))
    }

    @Test
    fun `missing commitment fails a required difficulty`() {
        val event = TestData.post(TestData.keys(1))
        assertTrue(Nip13.meets(event, 0))
        assertFalse(Nip13.meets(event, 1))
    }
}

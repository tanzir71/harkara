package io.github.tanzir71.harkara.core.keys

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class RecoveryCardsTest {
    private val seed = ByteArray(32) { (it * 13 + 5).toByte() }

    @Test
    fun `any two of three cards rebuild the seed`() {
        val cards = RecoveryCards.split(seed)

        assertEquals(listOf(1, 2, 3), cards.map { it.index })
        listOf(0 to 1, 0 to 2, 1 to 2, 2 to 0).forEach { (a, b) ->
            assertArrayEquals(seed, RecoveryCards.combine(listOf(cards[a], cards[b])))
        }
        assertArrayEquals(seed, RecoveryCards.combine(cards))
    }

    @Test
    fun `one card is not enough and reveals nothing directly`() {
        val cards = RecoveryCards.split(seed)
        assertThrows(IllegalArgumentException::class.java) { RecoveryCards.combine(listOf(cards[0])) }
        assertThrows(IllegalArgumentException::class.java) { RecoveryCards.combine(listOf(cards[1], cards[1])) }
        cards.forEach { assertFalse(it.share.contentEquals(seed)) }
        // Fresh randomness gives different cards for the same seed.
        assertFalse(RecoveryCards.split(seed)[0].share.contentEquals(cards[0].share))
    }

    @Test
    fun `cards survive text encoding and catch typos`() {
        val cards = RecoveryCards.split(seed, SecureRandom())
        val text = cards.map { it.encode() }

        text.forEach { assertEquals(true, it.startsWith("hkcard1")) }
        val decoded = text.map(RecoveryCards.Card::decode)
        assertEquals(cards, decoded)
        assertArrayEquals(seed, RecoveryCards.combine(decoded.drop(1)))
        val typo = text[0].substring(0, 20) + (if (text[0][20] == 'q') 'p' else 'q') + text[0].substring(21)
        assertThrows(IllegalArgumentException::class.java) { RecoveryCards.Card.decode(typo) }
    }

    @Test
    fun `gf256 field arithmetic`() {
        for (a in 1..255) {
            assertEquals(1, Gf256.mul(a, Gf256.div(1, a)), "inverse of $a")
        }
        assertEquals(0xc1, Gf256.mul(0x57, 0x83))
    }
}

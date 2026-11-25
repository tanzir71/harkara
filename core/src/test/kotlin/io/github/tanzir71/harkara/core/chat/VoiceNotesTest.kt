package io.github.tanzir71.harkara.core.chat

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class VoiceNotesTest {
    private val alice = KeyPair.generate()
    private val bob = KeyPair.generate()
    private val carol = KeyPair.generate()
    private val now = 1_764_000_000L

    /** 15 s of AAC at 32 kbps is the worst case we expect (~60 KB); Opus at 16 kbps is half that. */
    private fun clip(bytes: Int = 60_000, ms: Long = 15_000) = VoiceClip(Random(7).nextBytes(bytes), "audio/ogg", ms)

    @Test
    fun `split, wrap, unwrap and reassemble a 15 second note`() {
        val note = clip()
        val parts = VoiceNotes.split(note, alice.pubkeyHex, listOf(bob.pubkeyHex, carol.pubkeyHex), now, name = "Ma")
        assertEquals(15, parts.size)
        val wraps = parts.flatMap { GiftWrap.wrapForGroup(it, alice, listOf(bob.pubkeyHex, carol.pubkeyHex)) }
        assertEquals(45, wraps.size)
        wraps.forEach { wrap ->
            assertTrue(wrap.jsonSize() < Event.MAX_JSON_BYTES, "wrap is ${wrap.jsonSize()} bytes")
            assertTrue(wrap.validate())
        }

        val received = wraps.filter { it.tagValue("p") == bob.pubkeyHex }.shuffled(Random(3))
            .map { GiftWrap.unwrap(it, bob) }
            .map { ChatProtocol.parse(it) as ChatRumor.Voice }
        assertEquals("Ma", received.first().name)
        assertEquals(setOf(alice.pubkeyHex, bob.pubkeyHex, carol.pubkeyHex), received.first().members)
        assertNull(VoiceNotes.assemble(received.drop(1).map { it.part }))
        val joined = VoiceNotes.assemble(received.map { it.part })
        assertNotNull(joined)
        assertArrayEquals(note.bytes, joined!!.bytes)
        assertEquals("audio/ogg", joined.mime)
        assertEquals(15_000, joined.durationMs)
    }

    @Test
    fun `tampered or mixed parts are refused`() {
        val parts = VoiceNotes.split(clip(10_000, 4_000), alice.pubkeyHex, listOf(bob.pubkeyHex), now).map { VoiceNotes.parsePart(it)!! }
        val swapped = parts.toMutableList()
        val last = swapped.removeAt(swapped.lastIndex)
        swapped += VoicePart(last.voiceId, last.index, last.count, last.mime, last.durationMs, last.sha256, last.data.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertNull(VoiceNotes.assemble(swapped))

        val other = VoiceNotes.split(clip(10_000, 4_000), alice.pubkeyHex, listOf(bob.pubkeyHex), now).map { VoiceNotes.parsePart(it)!! }
        assertNull(VoiceNotes.assemble(parts.dropLast(1) + other.last()))
    }

    @Test
    fun `notes longer than 15 seconds or too big are not sent`() {
        assertThrows(IllegalArgumentException::class.java) { VoiceNotes.split(clip(ms = 16_000), alice.pubkeyHex, listOf(bob.pubkeyHex), now) }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceNotes.split(clip(bytes = VoiceNotes.MAX_BYTES + 1), alice.pubkeyHex, listOf(bob.pubkeyHex), now)
        }
        val tooLong = VoiceNotes.split(clip(1000, 1000), alice.pubkeyHex, listOf(bob.pubkeyHex), now).single()
        val forged = tooLong.copy(tags = tooLong.tags.map { if (it[0] == "duration") listOf("duration", "60000") else it })
        assertNull(VoiceNotes.parsePart(forged))
    }
}

package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.nostr.NipExamples
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BinaryEventCodecTest {
    private fun roundTrip(event: Event, includeId: Boolean = true): Event {
        val decoded = BinaryEventCodec.decode(BinaryEventCodec.encode(event, includeId))
        assertEquals(event, decoded)
        assertEquals(event.toJson(), decoded.toJson())
        assertTrue(decoded.verify(), "a re-encoded event still verifies; nobody re-signs")
        return decoded
    }

    @Test
    fun `json to binary to json is identical for real signed events`() {
        roundTrip(Event.fromJson(NipExamples.NIP13_NOTE))
        roundTrip(Event.fromJson(NipExamples.NIP59_SEAL))
        roundTrip(Event.fromJson(NipExamples.NIP59_WRAP))
        roundTrip(Event.fromJson(NipExamples.NIP17_WRAP_TO_RECEIVER))
        roundTrip(TestData.post(TestData.keys(1), text = "জলাবদ্ধতা 🚩 \"quote\" \n new line \u0001"))
        roundTrip(TestData.keys(2).signEvent(Kinds.REACTION, listOf(listOf("e", "ab".repeat(32)), listOf("p", "cd".repeat(32)), emptyList(), listOf("x", "", "y")), "+"))
        roundTrip(TestData.keys(3).signEvent(Kinds.FEED_POST, content = ""))
    }

    @Test
    fun `long text content is deflated and still round trips`() {
        val text = "Water point behind the lake mosque is open 7:00 to 10:00. ".repeat(40)
        val event = TestData.post(TestData.keys(4), text = text)

        val bytes = BinaryEventCodec.encode(event)

        assertEquals(BinaryEventCodec.FLAG_DEFLATE, bytes[1].toInt() and BinaryEventCodec.FLAG_DEFLATE)
        assertTrue(bytes.size < text.length / 4, "compressed ${bytes.size} bytes")
        roundTrip(event)
    }

    @Test
    fun `binary is much smaller than json`() {
        val wrap = GiftWrap.sealAndWrap(GiftWrap.createRumor(TestData.keys(1).pubkeyHex, Kinds.CHAT_MESSAGE, "ok"), TestData.keys(1), TestData.keys(2).pubkeyHex)
        val bytes = BinaryEventCodec.encode(wrap)

        // Hex ids/keys/sig become raw bytes, and base64 ciphertext deflates by about a quarter.
        assertTrue(bytes.size < wrap.toJson().length * 0.8, "binary ${bytes.size} vs json ${wrap.toJson().length}")
        roundTrip(wrap)
    }

    @Test
    fun `short content that does not shrink is stored raw`() {
        val bytes = BinaryEventCodec.encode(TestData.keys(1).signEvent(Kinds.REACTION, content = "+"))
        assertEquals(0, bytes[1].toInt() and BinaryEventCodec.FLAG_DEFLATE)
    }

    @Test
    fun `sms mode leaves the id out and the decoder recomputes it`() {
        val event = TestData.post(TestData.keys(5), text = "Traffic clear on Mirpur Rd")
        val full = BinaryEventCodec.encode(event)
        val compact = BinaryEventCodec.encode(event, includeId = false)

        assertEquals(full.size - 32, compact.size)
        roundTrip(event, includeId = false)
    }

    @Test
    fun `malformed input is rejected`() {
        val bytes = BinaryEventCodec.encode(TestData.post(TestData.keys(6)))
        assertThrows(IllegalArgumentException::class.java) { BinaryEventCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { BinaryEventCodec.decode(bytes + 0) }
        assertThrows(IllegalArgumentException::class.java) { BinaryEventCodec.decode(byteArrayOf(9) + bytes.copyOfRange(1, bytes.size)) }
        assertThrows(IllegalArgumentException::class.java) { BinaryEventCodec.decode(ByteArray(0)) }
        val tooLate = TestData.post(TestData.keys(6)).copy(createdAt = 0x1_0000_0000L)
        assertThrows(IllegalArgumentException::class.java) { BinaryEventCodec.encode(tooLate) }
    }

    @Test
    fun `a changed byte breaks verification instead of passing silently`() {
        val event = TestData.post(TestData.keys(7))
        val bytes = BinaryEventCodec.encode(event, includeId = false)
        bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte()
        assertEquals(false, runCatching { BinaryEventCodec.decode(bytes).verify() }.getOrDefault(false))
    }
}

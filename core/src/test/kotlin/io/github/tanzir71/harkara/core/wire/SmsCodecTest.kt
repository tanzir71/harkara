package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class SmsCodecTest {
    private val alice = TestData.keys(1)
    private val bob = TestData.keys(2)
    private val now = TestData.NOW
    private val everyone = PrefixResolver.of(listOf(alice.pubkeyHex, bob.pubkeyHex))

    private fun roundTrip(event: Event, refs: Set<String> = emptySet(), resolver: PrefixResolver = everyone): Event {
        val decoded = SmsCodec.decode(SmsCodec.encode(event, refs), resolver)
        assertEquals(event, decoded)
        assertEquals(event.toJson(), decoded.toJson())
        assertTrue(decoded.verify(), "the receiver gets the same signed event; nobody re-signs")
        return decoded
    }

    @Test
    fun `every item form round trips`() {
        val other = KeyPair.generate()
        val event = alice.signEvent(
            Kinds.TASK_STEP,
            listOf(
                listOf("a", "30402:${bob.pubkeyHex}:7c9e6679-7425-40de-944b-e07fc1f90ae7"),
                listOf("e", "ab".repeat(32), "", "accept"),
                listOf("p", bob.pubkeyHex),
                listOf("p", alice.pubkeyHex),
                listOf("x", other.pubkeyHex),
                listOf("x", other.pubkeyHex),
                listOf("n", "0", "7", "007", "1764000000", "18446744073709551615"),
                listOf("h", "deadbeefdeadbeef00", "DEADBEEFDEADBEEF", "abc"),
                listOf("u", "7C9E6679-7425-40DE-944B-E07FC1F90AE7", "7c9e6679742540de944be07fc1f90ae7"),
                listOf("j", "a:b", ":", "::x::", "1:2:3"),
                emptyList(),
                listOf(""),
                listOf("bn", "ধানমন্ডি ২৭ । ক্ষ ✓ emoji 🙂 DEL\u007f tab\t nul\u0000"),
            ),
            "বাংলা text, \"quotes\", newline\n and 🙂",
            now,
        )
        roundTrip(event)
        roundTrip(event, refs = setOf(bob.pubkeyHex, "ab".repeat(32), other.pubkeyHex), resolver = PrefixResolver.of(listOf(alice.pubkeyHex, bob.pubkeyHex, "ab".repeat(32), other.pubkeyHex)))
        roundTrip(alice.signEvent(Kinds.CHECK_IN, emptyList(), "", now))
        roundTrip(alice.signEvent(Kinds.SMS_DM, listOf(listOf("p", bob.pubkeyHex)), Base64.getEncoder().encodeToString(ByteArray(99) { it.toByte() }), now))
    }

    @Test
    fun `references are smaller and need the receiver to know the value`() {
        val sos = bob.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0dkq7")), "help", now)
        val update = bob.signEvent(Kinds.SOS_UPDATE, listOf(listOf("e", sos.id), listOf("status", "resolved")), "", now + 60)
        val full = SmsCodec.encode(update)
        val short = SmsCodec.encode(update, setOf(sos.id))
        // A full 32-byte value costs a 2-byte header + 32 bytes; a reference 1 + 8.
        assertEquals(full.size - 25, short.size)
        roundTrip(update, setOf(sos.id), PrefixResolver.of(listOf(bob.pubkeyHex, sos.id)))
        assertThrows(IllegalArgumentException::class.java) { SmsCodec.decode(short, PrefixResolver.of(listOf(bob.pubkeyHex))) }
        assertThrows(IllegalArgumentException::class.java) { SmsCodec.decode(full, PrefixResolver.of(listOf(alice.pubkeyHex))) }
    }

    @Test
    fun `two keys sharing the author prefix are told apart by the signature`() {
        val event = bob.signEvent(Kinds.CHECK_IN, listOf(listOf("g", "wh0r0dk")), "safe", now)
        // A colliding "key" with the same first 8 bytes (not a real key the signature could match).
        val impostor = bob.pubkeyHex.take(16) + "0".repeat(48)
        val decoded = SmsCodec.decode(SmsCodec.encode(event), PrefixResolver { listOf(impostor, bob.pubkeyHex) })
        assertEquals(event, decoded)
    }

    @Test
    fun `tampering with any byte fails the signature`() {
        val event = alice.signEvent(Kinds.CHECK_IN, listOf(listOf("g", "wh0r0dkq7"), listOf("place", "Shahbag")), "Reached the hall safely", now)
        val bytes = SmsCodec.encode(event)
        var rejected = 0
        for (i in bytes.indices) {
            val bad = bytes.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            val result = runCatching { SmsCodec.decode(bad, everyone) }
            if (result.isFailure) rejected++ else assertEquals(event, result.getOrThrow(), "a flipped bit can never yield a different valid event")
        }
        // Only a flip in DEFLATE's unused padding bits can decode to the same event; nothing else survives.
        assertTrue(rejected >= bytes.size - 1, "$rejected of ${bytes.size} flips rejected")
        assertThrows(IllegalArgumentException::class.java) { SmsCodec.decode(bytes.copyOf(bytes.size - 3), everyone) }
        assertThrows(IllegalArgumentException::class.java) { SmsCodec.decode(bytes + 1, everyone) }
        val text = SmsCodec.text(event)
        val forged = text.replaceRange(text.length - 6, text.length - 5, if (text[text.length - 6] == 'A') "B" else "A")
        assertNull(SmsCodec.decodeText(forged, everyone))
    }

    @Test
    fun `sms text is a readable Latin line plus the payload, found anywhere in pasted text`() {
        val event = alice.signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0dk")), "ছাদে আটকে আছি", now)
        val text = SmsCodec.text(event)
        assertTrue(text.startsWith("Harkara SOS HK1:"))
        assertTrue(Gsm7.isGsm7(text), "Bangla rides inside the payload; the SMS stays GSM-7")
        assertEquals(event, SmsCodec.decodeText("Fwd from 01711:\n$text\nsent 10:42", everyone))
        assertNull(SmsCodec.decodeText("no payload here", everyone))
        assertTrue(SmsCodec.looksLikeSms(text))
    }

    @Test
    fun `text8 maps Bangla to one byte and round trips everything else`() {
        val bangla = "ধানমন্ডি ২৭"
        assertEquals(bangla.length, Text8.encode(bangla).size)
        for (s in listOf("", "plain ASCII", "\u007f", "a\u007fb", "। ৳ ₹ 🙂 é", "০১২৩৪৫৬৭৮৯", "\u0980\u09ff")) {
            assertEquals(s, Text8.decode(Text8.encode(s)))
        }
        assertThrows(IllegalArgumentException::class.java) { Text8.encode("\ud800") }
        assertThrows(IllegalArgumentException::class.java) { Text8.decode(byteArrayOf(0x7f)) }
    }

    @Test
    fun `gsm-7 detection and part counting`() {
        assertTrue(Gsm7.isGsm7("Harkara SOS HK1:AZaz09-_"))
        assertTrue(Gsm7.isGsm7("Price {100} €"))
        assertFalse(Gsm7.isGsm7("Harkara SOS — open"), "an em dash flips the SMS to UCS-2")
        assertFalse(Gsm7.isGsm7("ধান"))
        assertEquals(1, Gsm7.parts("x".repeat(160)))
        assertEquals(2, Gsm7.parts("x".repeat(161)))
        assertEquals(2, Gsm7.parts("x".repeat(306)))
        assertEquals(3, Gsm7.parts("x".repeat(307)))
        assertEquals(2, Gsm7.parts("€".repeat(80) + "x"), "extension characters cost two septets")
        assertEquals(1, Gsm7.parts("ধ".repeat(70)))
        assertEquals(2, Gsm7.parts("ধ".repeat(71)))
        assertEquals(161, Gsm7.septets("{" + "x".repeat(159)))
        assertEquals(-1, Gsm7.septets("ধ"))
    }

    @Test
    fun `unknown version, flags and items are refused`() {
        val event = alice.signEvent(Kinds.CHECK_IN, emptyList(), "ok", now)
        val bytes = SmsCodec.encode(event)
        assertThrows(IllegalArgumentException::class.java) { SmsCodec.decode(byteArrayOf(0x20) + bytes.copyOfRange(1, bytes.size), everyone) }
        assertThrows(IllegalArgumentException::class.java) { SmsCodec.decode(byteArrayOf(0x1e) + bytes.copyOfRange(1, bytes.size), everyone) }
        assertThrows(IllegalArgumentException::class.java) { SmsCodec.decode(ByteArray(0), everyone) }
    }
}

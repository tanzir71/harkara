package io.github.tanzir71.harkara.core.nostr

import io.github.tanzir71.harkara.core.TestData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventTest {
    @Test
    fun `NIP-13 example note recomputes its id and verifies its signature`() {
        val event = Event.fromJson(NipExamples.NIP13_NOTE)

        assertEquals(event.id, event.computeId())
        assertTrue(event.verify())
        assertEquals(EventCheck.OK, event.check())
        assertEquals("776797", event.tags.single()[1])
    }

    @Test
    fun `NIP-59 seal and wrap examples verify and the rumor id matches`() {
        val seal = Event.fromJson(NipExamples.NIP59_SEAL)
        val wrap = Event.fromJson(NipExamples.NIP59_WRAP)
        val rumor = UnsignedEvent(
            pubkey = "611df01bfcf85c26ae65453b772d8f1dfd25c264621c0277e1fc1518686faef9",
            createdAt = 1691518405,
            kind = 1,
            tags = emptyList(),
            content = "Are you going to the party tonight?",
        )

        assertTrue(seal.verify())
        assertTrue(wrap.verify())
        assertEquals(NipExamples.NIP59_RUMOR_ID, rumor.id)
        assertEquals(KeyPair.fromHex(NipExamples.NIP59_AUTHOR_SECRET).pubkeyHex, seal.pubkey)
        assertEquals(KeyPair.fromHex(NipExamples.NIP59_WRAPPER_SECRET).pubkeyHex, wrap.pubkey)
    }

    @Test
    fun `canonical serialization escapes exactly as NIP-01 and JSON stringify`() {
        val content = "line\nquote\" back\\ cr\r tab\t bs\b ff\u000C nul\u0000 us\u001F del\u007F বাংলা 🚩 \u2028"
        val serialized = EventId.serialize("ab".repeat(32), 1, 1, listOf(listOf("t", "a\"b")), content)

        assertEquals(
            "[0,\"${"ab".repeat(32)}\",1,1,[[\"t\",\"a\\\"b\"]]," +
                "\"line\\nquote\\\" back\\\\ cr\\r tab\\t bs\\b ff\\f nul\\u0000 us\\u001f del\u007F বাংলা 🚩 \u2028\"]",
            serialized,
        )
    }

    @Test
    fun `lone surrogates are escaped like JSON stringify`() {
        val serialized = EventId.serialize("ab".repeat(32), 1, 1, emptyList(), "x\uD83Dy")
        assertTrue(serialized.endsWith(",\"x\\ud83dy\"]"))
    }

    @Test
    fun `json round trip keeps every field and the signature`() {
        val keys = TestData.keys(7)
        val event = keys.signEvent(
            kind = Kinds.FEED_POST,
            tags = listOf(listOf("g", "wh0r0d"), listOf("t", "alert"), listOf("expiration", "1795597200")),
            content = "জলাবদ্ধতা Road 27 \"ankle deep\"\nRickshaws still crossing",
            createdAt = TestData.NOW,
        )

        val parsed = Event.fromJson(event.toJson())

        assertEquals(event, parsed)
        assertTrue(parsed.verify())
        assertEquals("wh0r0d", parsed.tagValue("g"))
        assertEquals(listOf("alert"), parsed.tagValues("t"))
        assertEquals(1_795_597_200L, parsed.expiration)
        assertTrue(parsed.isExpired(1_795_597_200L))
        assertFalse(parsed.isExpired(1_795_597_199L))
    }

    @Test
    fun `tampering breaks the id or the signature`() {
        val event = TestData.post(TestData.keys(3))

        assertEquals(EventCheck.BAD_ID, event.copy(content = "changed").check())
        assertFalse(event.copy(content = "changed").verify())
        val otherSig = TestData.post(TestData.keys(3), text = "other").sig
        assertEquals(EventCheck.BAD_SIGNATURE, event.copy(sig = otherSig).check())
        assertEquals(EventCheck.BAD_SIGNATURE, event.copy(pubkey = TestData.keys(4).pubkeyHex, id = event.copy(pubkey = TestData.keys(4).pubkeyHex).computeId()).check())
        assertEquals(EventCheck.MALFORMED, event.copy(id = event.id.uppercase()).check())
        assertEquals(EventCheck.MALFORMED, event.copy(kind = 70_000).check())
    }

    @Test
    fun `events of 16 KB or more are rejected`() {
        val keys = TestData.keys(5)
        val small = keys.signEvent(Kinds.FEED_POST, content = "x".repeat(15_000))
        val big = keys.signEvent(Kinds.FEED_POST, content = "x".repeat(16 * 1024))

        assertTrue(small.validate())
        assertTrue(big.verify())
        assertEquals(EventCheck.TOO_LARGE, big.check())
        assertFalse(big.validate())
    }

    @Test
    fun `fromJson rejects missing or mistyped fields`() {
        val json = NipExamples.NIP13_NOTE
        assertThrows(IllegalArgumentException::class.java) { Event.fromJson(json.replace("\"kind\":1", "\"kind\":\"1\"")) }
        assertThrows(IllegalArgumentException::class.java) { Event.fromJson(json.replace("\"sig\"", "\"signature\"")) }
        assertThrows(IllegalArgumentException::class.java) { Event.fromJson("[1,2,3]") }
        assertThrows(IllegalArgumentException::class.java) { Event.fromJson("{not json") }
        assertThrows(IllegalArgumentException::class.java) { Event.fromJson(json.replace("[[\"nonce\",\"776797\",\"20\"]]", "[[\"nonce\",776797]]")) }
    }

    @Test
    fun `tag helpers and missing expiration`() {
        val event = TestData.post(TestData.keys(2), expiresAt = null)
        assertNull(event.expiration)
        assertFalse(event.isExpired(Long.MAX_VALUE))
        assertNull(event.tagValue("missing"))
    }

    @Test
    fun `rumors parse with a matching id and refuse signatures`() {
        val keys = TestData.keys(9)
        val rumor = GiftWrap.createRumor(keys.pubkeyHex, Kinds.CHAT_MESSAGE, "hi", createdAt = TestData.NOW)

        assertEquals(rumor, UnsignedEvent.fromJson(rumor.toJson()))
        assertThrows(IllegalArgumentException::class.java) { UnsignedEvent.fromJson(rumor.toJson().replace("\"hi\"", "\"ho\"")) }
        val signed = rumor.sign(keys)
        assertThrows(IllegalArgumentException::class.java) { UnsignedEvent.fromJson(signed.toJson()) }
    }
}

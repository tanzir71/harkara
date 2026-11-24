package io.github.tanzir71.harkara.core.nostr

import io.github.tanzir71.harkara.core.TestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Official NIP-44 v2 vectors: nip44.vectors.json from github.com/paulmillr/nip44 (MIT), the file whose
 * SHA-256 the NIP itself pins (269ed0f6…5040).
 */
class Nip44VectorsTest {
    private val v2: JsonObject = Json.parseToJsonElement(TestData.resource("nip44.vectors.json")).jsonObject["v2"]!!.jsonObject
    private val valid = v2["valid"]!!.jsonObject
    private val invalid = v2["invalid"]!!.jsonObject

    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content
    private fun list(group: JsonObject, key: String): List<JsonObject> = group[key]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `vector file is the one pinned by the NIP`() {
        val sha = Hex.encode(Hashes.sha256(TestData.resource("nip44.vectors.json").encodeToByteArray()))
        assertEquals("269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040", sha)
    }

    @Test
    fun `valid get_conversation_key`() {
        val vectors = list(valid, "get_conversation_key")
        assertEquals(35, vectors.size)
        vectors.forEach { v ->
            val key = Nip44.conversationKey(Hex.decode(v.s("sec1")), v.s("pub2"))
            assertEquals(v.s("conversation_key"), Hex.encode(key), v.toString())
        }
    }

    @Test
    fun `valid get_message_keys`() {
        val group = valid["get_message_keys"]!!.jsonObject
        val conversationKey = Hex.decode(group.s("conversation_key"))
        val keys = group["keys"]!!.jsonArray.map { it.jsonObject }
        assertEquals(32, keys.size)
        keys.forEach { v ->
            val k = Nip44.messageKeys(conversationKey, Hex.decode(v.s("nonce")))
            assertEquals(v.s("chacha_key"), Hex.encode(k.chachaKey))
            assertEquals(v.s("chacha_nonce"), Hex.encode(k.chachaNonce))
            assertEquals(v.s("hmac_key"), Hex.encode(k.hmacKey))
        }
    }

    @Test
    fun `valid calc_padded_len`() {
        val vectors = valid["calc_padded_len"]!!.jsonArray
        assertEquals(24, vectors.size)
        vectors.forEach { pair ->
            val (len, padded) = (pair as JsonArray).map { it.jsonPrimitive.int }
            assertEquals(padded, Nip44.calcPaddedLen(len), "len $len")
        }
    }

    @Test
    fun `valid encrypt_decrypt`() {
        val vectors = list(valid, "encrypt_decrypt")
        assertEquals(10, vectors.size)
        vectors.forEach { v ->
            val sec1 = Hex.decode(v.s("sec1"))
            val sec2 = Hex.decode(v.s("sec2"))
            val key = Nip44.conversationKey(sec1, KeyPair(sec2).pubkeyHex)
            assertEquals(v.s("conversation_key"), Hex.encode(key))
            // Both sides derive the same key.
            assertArrayEquals(key, Nip44.conversationKey(sec2, KeyPair(sec1).pubkeyHex))
            val payload = Nip44.encrypt(v.s("plaintext"), key, Hex.decode(v.s("nonce")))
            assertEquals(v.s("payload"), payload)
            assertEquals(v.s("plaintext"), Nip44.decrypt(v.s("payload"), key))
        }
    }

    @Test
    fun `valid encrypt_decrypt_long_msg`() {
        val vectors = list(valid, "encrypt_decrypt_long_msg")
        assertEquals(3, vectors.size)
        vectors.forEach { v ->
            val plaintext = v.s("pattern").repeat(v["repeat"]!!.jsonPrimitive.int)
            assertEquals(v.s("plaintext_sha256"), Hex.encode(Hashes.sha256(plaintext.encodeToByteArray())))
            val key = Hex.decode(v.s("conversation_key"))
            val payload = Nip44.encrypt(plaintext, key, Hex.decode(v.s("nonce")))
            assertEquals(v.s("payload_sha256"), Hex.encode(Hashes.sha256(payload.encodeToByteArray())))
            assertEquals(plaintext, Nip44.decrypt(payload, key))
        }
    }

    @Test
    fun `invalid encrypt_msg_lengths`() {
        val lengths = invalid["encrypt_msg_lengths"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(4, lengths.size)
        val key = ByteArray(32) { 1 }
        lengths.forEach { len ->
            assertThrows(Nip44Exception::class.java, { Nip44.encrypt("a".repeat(len), key) }, "length $len")
        }
    }

    @Test
    fun `invalid get_conversation_key`() {
        val vectors = list(invalid, "get_conversation_key")
        assertEquals(8, vectors.size)
        vectors.forEach { v ->
            assertThrows(Nip44Exception::class.java, { Nip44.conversationKey(Hex.decode(v.s("sec1")), v.s("pub2")) }, v.s("note"))
        }
    }

    @Test
    fun `invalid decrypt`() {
        val vectors = list(invalid, "decrypt")
        assertEquals(12, vectors.size)
        vectors.forEach { v ->
            assertThrows(Nip44Exception::class.java, { Nip44.decrypt(v.s("payload"), Hex.decode(v.s("conversation_key"))) }, v.s("note"))
        }
    }

    @Test
    fun `random nonces give different payloads that both decrypt`() {
        val key = Nip44.conversationKey(TestData.keys(1).privateKey, TestData.keys(2).pubkeyHex)
        val a = Nip44.encrypt("same text", key)
        val b = Nip44.encrypt("same text", key)
        assertNotEquals(a, b)
        assertEquals("same text", Nip44.decrypt(a, key))
        assertEquals("same text", Nip44.decrypt(b, key))
    }
}

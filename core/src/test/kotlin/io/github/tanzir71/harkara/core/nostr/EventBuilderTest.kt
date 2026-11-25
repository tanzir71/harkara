package io.github.tanzir71.harkara.core.nostr

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.keys.KeyRing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventBuilderTest {
    private val areaKey = KeyRing(ByteArray(32) { 3 }).area("wh0r0", "2026-W48")

    @Test
    fun `feed post carries area tags, category expiry and proof of work`() {
        val post = EventBuilder.feedPost(areaKey, "Gridlock at Science Lab", "wh0r0d", Category.TRAFFIC, TestData.NOW, powBits = 8)

        assertTrue(post.validate())
        assertEquals(Kinds.FEED_POST, post.kind)
        assertEquals(areaKey.pubkeyHex, post.pubkey)
        assertEquals(listOf("wh0r0d", "wh0r0", "wh0r"), post.tagValues("g"))
        assertEquals("traffic", post.tagValue("t"))
        assertEquals(TestData.NOW + 3 * 3600, post.expiration)
        assertTrue(Nip13.meets(post, 8))
    }

    @Test
    fun `food never expires and bad geohashes are refused`() {
        val post = EventBuilder.feedPost(areaKey, "Fuchka stall open late", "wh0r0d", Category.FOOD, TestData.NOW, powBits = 0)
        assertNull(post.expiration)
        assertThrows(IllegalArgumentException::class.java) { EventBuilder.feedPost(areaKey, "x", "wh0r", Category.FOOD, powBits = 0) }
    }

    @Test
    fun `votes and reports reference the post`() {
        val post = TestData.post(TestData.keys(1))
        val vote = EventBuilder.vote(TestData.keys(2), post, up = false)
        val report = EventBuilder.report(TestData.keys(3), post, "spam")

        assertEquals("-", vote.content)
        assertEquals(post.id, vote.tagValue("e"))
        assertEquals("1", vote.tagValue("k"))
        assertEquals(listOf("e", post.id, "spam"), report.tags.first())
        assertEquals(post.pubkey, report.tagValue("p"))
        assertTrue(vote.validate() && report.validate())
        assertThrows(IllegalArgumentException::class.java) { EventBuilder.report(TestData.keys(3), post, "rude") }
    }
}

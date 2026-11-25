package io.github.tanzir71.harkara.core.feed

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Nip13
import io.github.tanzir71.harkara.core.store.StorageBudget
import io.github.tanzir71.harkara.core.store.StoredItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FeedRulesTest {
    private val now = 1_764_000_000L
    private val dhanmondi = 23.7465 to 90.376
    private val filters: WordFilters = WordFilters.parse(
        File("../data/word-filters/filters.json").takeIf { it.exists() }?.readText()
            ?: error("data/word-filters/filters.json missing"),
    )

    private fun post(category: Category?, text: String = "Octane available at Satmasjid Road", bits: Int = 8, at: Long = now) =
        PostComposer.compose(KeyPair.generate(), text, category, dhanmondi.first, dhanmondi.second, at, mapOf("stock" to "fuel", "status" to "available"), bits)

    @Test
    fun `composed post carries geohash tags, category, expiry, details and proof of work`() {
        val event = post(Category.STOCK)
        assertTrue(event.verify())
        assertEquals(Kinds.FEED_POST, event.kind)
        assertEquals(listOf(7, 6, 5), event.tagValues("g").map { it.length })
        assertEquals("stock", event.tagValue("t"))
        assertEquals(now + 24 * 3600, event.expiration)
        assertTrue(Nip13.difficulty(event.id) >= 8)
        val parsed = FeedPost.from(event)!!
        assertEquals(Category.STOCK, parsed.category)
        assertEquals("fuel", parsed.details["stock"])
        assertEquals(7, parsed.geohash.length)
    }

    @Test
    fun `expiry follows loka categories and food never expires`() {
        assertEquals(now + 3 * 3600, PostComposer.expirationFor(Category.TRAFFIC, now))
        assertEquals(now + 4 * 3600, PostComposer.expirationFor(Category.CROWD, now))
        assertEquals(now + 12 * 3600, PostComposer.expirationFor(Category.ALERT, now))
        assertEquals(now + 48 * 3600, PostComposer.expirationFor(null, now))
        assertNull(PostComposer.expirationFor(Category.FOOD, now))
        assertNull(post(Category.FOOD).expiration)
        assertTrue(post(Category.TRAFFIC, at = now - 4 * 3600).isExpired(now))
    }

    @Test
    fun `pow difficulty is tuned between 16 and 20 bits`() {
        assertEquals(16, PostComposer.tunePowBits(10_000.0))
        assertEquals(19, PostComposer.tunePowBits(300_000.0))
        assertEquals(20, PostComposer.tunePowBits(5_000_000.0))
    }

    @Test
    fun `composer rules from loka`() {
        val filter = ContentFilter(filters)
        assertEquals(ComposeVerdict.OK, filter.checkDraft("Jam at Nilkhet, take Green Road", Category.TRAFFIC))
        assertEquals(ComposeVerdict.EMPTY, filter.checkDraft("   ", null))
        assertEquals(ComposeVerdict.TOO_LONG, filter.checkDraft("a".repeat(401), null))
        assertEquals(ComposeVerdict.BLOCKED_WORDS, filter.checkDraft("what the fuck", null))
        assertEquals(ComposeVerdict.SPAM, filter.checkDraft("Earn money fast, CLICK HERE", null))
        assertEquals(ComposeVerdict.TOO_MANY_LINKS, filter.checkDraft("see a.com and b.com", Category.FOOD))
        assertEquals(ComposeVerdict.PHONE_NUMBER, filter.checkDraft("call 01712 345678", Category.ALERT))
        assertEquals(ComposeVerdict.PHONE_NUMBER, filter.checkDraft("ফোন ০১৭১২৩৪৫৬৭৮", null))
        assertEquals(ComposeVerdict.OK, filter.checkDraft("call 01712 345678", Category.STOCK))
        assertTrue(filters.banglaBlockedFragments.isNotEmpty())
        assertEquals(ComposeVerdict.BLOCKED_WORDS, filter.checkDraft("ei ${filters.banglaBlockedFragments.first()} ta", null))
    }

    @Test
    fun `mute words hide incoming posts locally`() {
        val filter = ContentFilter(filters, muteWords = setOf("biryani"))
        assertTrue(filter.hidesIncoming("Best BIRYANI in Old Dhaka"))
        assertFalse(filter.hidesIncoming("Khichuri at the canteen"))
    }

    @Test
    fun `three distinct reporters hide a post, repeated reports by one key do not`() {
        val target = post(Category.ALERT)
        val ladder = ModerationLadder(filters.moderation)
        val alice = KeyPair.generate()
        val same = (1..5).map { Reactions.report(alice, target, ReportReason.SPAM, now + it) }
        assertFalse(ladder.evaluate(target, same).hidden)
        assertEquals(1, ladder.evaluate(target, same).reporters)

        val three = (1..3).map { Reactions.report(KeyPair.generate(), target, ReportReason.SPAM, now) }
        val state = ladder.evaluate(target, three)
        assertEquals(3, state.reporters)
        assertTrue(state.hidden)
    }

    @Test
    fun `score of minus five hides and each key's latest vote counts once`() {
        val target = post(Category.CHATTER)
        val ladder = ModerationLadder()
        val downs = (1..5).map { Reactions.vote(KeyPair.generate(), target, up = false, now) }
        assertTrue(ladder.evaluate(target, downs).hidden)
        assertEquals(-5, ladder.evaluate(target, downs).score)

        val flipper = KeyPair.generate()
        val flips = listOf(Reactions.vote(flipper, target, false, now), Reactions.vote(flipper, target, true, now + 10))
        val state = ladder.evaluate(target, flips, me = setOf(flipper.pubkeyHex))
        assertEquals(1, state.score)
        assertEquals(1, state.myVote)
        assertFalse(state.hidden)
    }

    @Test
    fun `moderator reports and blocked authors hide immediately`() {
        val target = post(Category.ALERT)
        val mod = KeyPair.generate()
        assertTrue(ModerationLadder(moderators = setOf(mod.pubkeyHex)).evaluate(target, listOf(Reactions.report(mod, target, ReportReason.ILLEGAL, now))).hidden)
        assertTrue(ModerationLadder().evaluate(target, emptyList(), blockedAuthors = setOf(target.pubkey)).hidden)
        assertNotNull(Reactions.target(Reactions.vote(mod, target, true, now)))
    }

    @Test
    fun `storage budget evicts oldest of other peoples events and never our own`() {
        val budget = StorageBudget(maxOthersEventBytes = 100)
        val items = listOf(
            StoredItem("mine-old", 500, 1, own = true),
            StoredItem("a", 40, 10, own = false),
            StoredItem("b", 40, 20, own = false),
            StoredItem("c", 40, 30, own = false),
        )
        assertEquals(listOf("a"), budget.evictions(items))
        assertEquals(emptyList<String>(), budget.evictions(items.filter { it.own }))
        assertEquals(20L * 1024 * 1024, StorageBudget().maxOthersEventBytes)
        assertEquals(100L * 1024 * 1024, StorageBudget().maxMediaBytes)
    }
}

package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class Phase7UtilitiesTest {
    @Test
    fun `sos policy rate limits active alerts and allows resolve flow`() {
        val sender = NearId(ByteArray(16) { 4 })
        val policy = SosPolicy(cooldown = Duration.ofMinutes(30))
        val now = Instant.ofEpochMilli(1_000)

        assertTrue(policy.accept(SosEvent(sender, SosKind.SEND, now)))
        assertFalse(policy.accept(SosEvent(sender, SosKind.SEND, now.plusSeconds(60))))
        assertTrue(policy.accept(SosEvent(sender, SosKind.RESOLVE, now.plusSeconds(120))))
        assertTrue(policy.accept(SosEvent(sender, SosKind.SEND, now.plusSeconds(180))))
    }

    @Test
    fun `muted sos senders are dropped`() {
        val sender = NearId(ByteArray(16) { 5 })
        val policy = SosPolicy(cooldown = Duration.ofMinutes(30), mutedSenders = setOf(sender))

        assertFalse(policy.accept(SosEvent(sender, SosKind.SEND, Instant.EPOCH)))
    }

    @Test
    fun `disaster mode widens geohash and maxes mesh duty`() {
        val policy = DisasterModePolicy(enabled = true)

        assertEquals(4, policy.feedPrefixLength(normalPrefixLength = 6))
        assertEquals(MeshDuty.MAXIMUM, policy.meshDuty(normal = MeshDuty.BALANCED))
        assertTrue(policy.showSosTab)
    }

    @Test
    fun `bulletins pin before regular posts and events sort by start time`() {
        val now = Instant.ofEpochMilli(1_000)
        val regular = social("regular", PostKind.STANDARD, now)
        val bulletin = social("bulletin", PostKind.BULLETIN, now.minusSeconds(1))
        val event = social("event", PostKind.EVENT, now.plusSeconds(3600), eventStartTime = now.plusSeconds(7200))

        assertEquals(listOf("bulletin", "event", "regular"), FeedOrdering.sortForFeed(listOf(regular, bulletin, event), now).map { it.postId })
        assertEquals(listOf("event"), FeedOrdering.upcomingEvents(listOf(regular, event), now).map { it.postId })
    }

    private fun social(
        id: String,
        kind: PostKind,
        createdAt: Instant,
        eventStartTime: Instant? = null,
    ): SocialPost = SocialPost(
        postId = id,
        author = NearId(ByteArray(16) { 9 }),
        displayName = "Aster",
        text = id,
        geohash = "9q8yyk",
        createdAt = createdAt,
        expiresAt = createdAt.plusSeconds(3600),
        signature = ByteArray(64),
        kind = kind,
        eventStartTime = eventStartTime,
    )
}

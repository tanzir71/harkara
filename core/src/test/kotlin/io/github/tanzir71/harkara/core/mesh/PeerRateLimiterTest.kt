package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerRateLimiterTest {
    private val author = TestData.keys(1)
    private fun post(at: Long, n: Int = 0) = TestData.post(author, text = "post $at/$n", createdAt = TestData.NOW + at)

    @Test
    fun `one post a minute`() {
        val limiter = PeerRateLimiter()
        assertTrue(limiter.tryAccept(post(0)))
        assertFalse(limiter.tryAccept(post(59)))
        assertTrue(limiter.tryAccept(post(60)))
        assertFalse(limiter.tryAccept(post(30)), "a back-dated post inside the minute is refused too")
    }

    @Test
    fun `ten posts an hour`() {
        val limiter = PeerRateLimiter()
        val accepted = (0 until 12).count { limiter.tryAccept(post(it * 120L)) }
        assertEquals(10, accepted)
        assertTrue(limiter.tryAccept(post(3600L + 120)), "the window slides")
    }

    @Test
    fun `thirty posts a day`() {
        val limiter = PeerRateLimiter()
        val accepted = (0 until 40).count { limiter.tryAccept(post(it * 1800L)) }
        assertEquals(30, accepted)
        assertTrue(limiter.tryAccept(post(24 * 3600L + 30 * 60)))
    }

    @Test
    fun `a backlog written over hours passes when it arrives at once`() {
        val limiter = PeerRateLimiter()
        val backlog = (0 until 8).map { post(it * 900L) }.shuffled(kotlin.random.Random(3))
        assertTrue(backlog.all(limiter::tryAccept))
    }

    @Test
    fun `limits are per key, repeats do not count, other kinds pass`() {
        val limiter = PeerRateLimiter()
        val first = post(0)
        assertTrue(limiter.tryAccept(first))
        assertTrue(limiter.tryAccept(first), "the same event again is not a second post")
        assertTrue(limiter.tryAccept(TestData.post(TestData.keys(2), createdAt = TestData.NOW + 1)))
        assertTrue(limiter.tryAccept(author.signEvent(Kinds.REACTION, content = "+", createdAt = TestData.NOW + 2)))
    }
}

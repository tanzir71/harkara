package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds

/** One sliding window: at most [limit] events in any [seconds]-long span. */
data class RateWindow(val seconds: Long, val limit: Int)

/**
 * The posting limits every peer enforces before relaying: per pubkey, 1 post a minute, 10 an hour and
 * 30 a day (handover §4). Windows slide over the events' `created_at`, so a burst that was written over
 * hours and arrives at once during a partition heal still passes, while a burst written at once does not.
 * Only [limitedKinds] are counted (feed posts by default); SOS has its own policy.
 */
class PeerRateLimiter(
    private val windows: List<RateWindow> = DEFAULT_WINDOWS,
    private val limitedKinds: Set<Int> = setOf(Kinds.FEED_POST),
    private val maxTracked: Int = 5_000,
) {
    private val history = LinkedHashMap<String, Author>(64, 0.75f, true)
    private val keepPerAuthor = maxOf(64, windows.maxOf { it.limit } * 4)

    private class Author {
        val ids = LinkedHashSet<String>()
        val times = ArrayList<Long>()
    }

    /** True when [event] is not limited or fits the limits; accepted events are counted. */
    fun tryAccept(event: Event): Boolean {
        if (event.kind !in limitedKinds) return true
        val author = history.getOrPut(event.pubkey) { Author() }
        if (event.id in author.ids) return true
        val t = event.createdAt
        val position = author.times.binarySearch(t).let { if (it < 0) -it - 1 else it }
        val candidate = ArrayList(author.times).apply { add(position, t) }
        for (w in windows) {
            // Any run of limit + 1 consecutive posts that includes the new one must span at least the window.
            val from = maxOf(0, position - w.limit)
            val to = minOf(position, candidate.size - 1 - w.limit)
            for (start in from..to) {
                if (candidate[start + w.limit] - candidate[start] < w.seconds) return false
            }
        }
        author.times.add(position, t)
        author.ids += event.id
        if (author.times.size > keepPerAuthor) author.times.removeAt(0)
        if (author.ids.size > keepPerAuthor) author.ids.remove(author.ids.first())
        while (history.size > maxTracked) {
            val eldest = history.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return true
    }

    companion object {
        val DEFAULT_WINDOWS: List<RateWindow> = listOf(
            RateWindow(seconds = 60, limit = 1),
            RateWindow(seconds = 60 * 60, limit = 10),
            RateWindow(seconds = 24 * 60 * 60, limit = 30),
        )
    }
}

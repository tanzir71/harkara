package io.github.tanzir71.harkara.core.store

/** One stored item as the budget sees it. */
data class StoredItem(val id: String, val bytes: Long, val receivedAt: Long, val own: Boolean)

/**
 * Storage budget (handover §4): at most 20 MB of other people's events and 100 MB of media, evicting the
 * oldest first. The user's own data is never auto-cleared, whatever its size.
 */
class StorageBudget(
    val maxOthersEventBytes: Long = 20L * 1024 * 1024,
    val maxMediaBytes: Long = 100L * 1024 * 1024,
) {
    /** Ids to delete (oldest received first) so other people's items fit in [limit]. */
    fun evictions(items: List<StoredItem>, limit: Long = maxOthersEventBytes): List<String> {
        val others = items.filterNot { it.own }
        var total = others.sumOf { it.bytes }
        if (total <= limit) return emptyList()
        val out = mutableListOf<String>()
        for (item in others.sortedWith(compareBy({ it.receivedAt }, { it.id }))) {
            if (total <= limit) break
            out += item.id
            total -= item.bytes
        }
        return out
    }

    fun mediaEvictions(items: List<StoredItem>): List<String> = evictions(items, maxMediaBytes)
}

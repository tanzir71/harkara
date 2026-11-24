package io.github.tanzir71.harkara.core

import java.time.Duration

/**
 * Nearby feed categories (from loka `lib/categories.ts`). The wire tag value is [tag];
 * [expiry] is null for categories that never expire.
 */
enum class Category(val tag: String, val label: String, val labelBn: String, val expiry: Duration?) {
    TRAFFIC("traffic", "Traffic", "জ্যাম", Duration.ofHours(3)),
    FOOD("food", "Food", "খাবার", null),
    CROWD("crowd", "Crowd", "ভিড়", Duration.ofHours(4)),
    STOCK("stock", "Stock", "স্টক", Duration.ofHours(24)),
    ALERT("alert", "Alert", "সতর্কতা", Duration.ofHours(12)),
    CHATTER("chatter", "Chatter", "আড্ডা", Duration.ofHours(48)),
    ;

    companion object {
        /** Resolves a `t` tag value; loka's legacy ids `busy` and `general` map to Crowd and Chatter. */
        fun fromTag(tag: String): Category? = when (tag) {
            "busy" -> CROWD
            "general" -> CHATTER
            else -> entries.firstOrNull { it.tag == tag }
        }
    }
}

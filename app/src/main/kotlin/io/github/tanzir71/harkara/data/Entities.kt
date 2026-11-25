package io.github.tanzir71.harkara.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every signed event the phone holds, ours and other people's. [json] is the exact NIP-01 JSON so a
 * bridging phone can re-send it without re-signing; the other columns are indexes for queries.
 */
@Entity(
    tableName = "events",
    indices = [Index("kind"), Index("geohash5"), Index("target"), Index("receivedAt"), Index("pubkey")],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val pubkey: String,
    val kind: Int,
    val createdAt: Long,
    val json: String,
    /** Most precise `g` tag, if any. */
    val geohash: String?,
    val geohash5: String?,
    /** `t` tag (feed category). */
    val category: String?,
    /** First `e` tag: the post a vote or report points at. */
    val target: String?,
    /** NIP-40 expiration, unix seconds. */
    val expiresAt: Long?,
    /** When this phone first saw it, epoch millis. */
    val receivedAt: Long,
    val bytes: Int,
    /** Made on this phone. Own data is never auto-cleared. */
    val own: Boolean,
    /** Carrier it arrived by (or left by, for our own), see [io.github.tanzir71.harkara.core.Carrier]. */
    val carrier: String,
)

/** Our events waiting to reach the network, with their delivery state. */
@Entity(tableName = "outbox", indices = [Index("state")])
data class OutboxEntity(
    @PrimaryKey val eventId: String,
    val json: String,
    /** Higher first: SOS > messages > posts. */
    val priority: Int,
    val attempts: Int,
    val nextAttemptAt: Long,
    /** [io.github.tanzir71.harkara.ui.components.Delivery] name. */
    val state: String,
    /** Carrier that moved it last. */
    val carrier: String?,
    val createdAt: Long,
)

/** One relay's answer (`OK`) for one of our events. */
@Entity(tableName = "relay_acks", primaryKeys = ["eventId", "relayUrl"])
data class RelayAckEntity(
    val eventId: String,
    val relayUrl: String,
    val accepted: Boolean,
    val message: String,
    val at: Long,
)

/** Local mutes and blocks: `type` is `author` (pubkey) or `word`. Never published. */
@Entity(tableName = "mutes", primaryKeys = ["type", "value"])
data class MuteEntity(
    val type: String,
    val value: String,
    val createdAt: Long,
) {
    companion object {
        const val AUTHOR = "author"
        const val WORD = "word"
    }
}

package io.github.tanzir71.harkara.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey val nearId: String,
    val displayName: String,
    val ed25519Public: ByteArray,
    val x25519Public: ByteArray,
    val lastSeenAt: Long,
    val optedIntoLocation: Boolean,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val msgId: Long,
    val senderId: String,
    val channel: String?,
    val recipientId: String?,
    val body: String,
    val state: String,
    val createdAt: Long,
)

@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey val postId: String,
    val author: String,
    val displayName: String,
    val text: String,
    val geohash: String,
    val kind: String,
    val imageRef: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val signature: ByteArray,
)

@Entity(tableName = "reactions", primaryKeys = ["postId", "author", "kind"])
data class ReactionEntity(
    val postId: String,
    val author: String,
    val kind: String,
    val count: Int,
    val updatedAt: Long,
)

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val name: String,
    val joinedAt: Long,
    val lastReadAt: Long,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val msgId: Long,
    val packetType: Int,
    val priority: Int,
    val payload: ByteArray,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val deliveredAt: Long?,
)

@Entity(tableName = "dedupe_seen")
data class DedupeSeenEntity(
    @PrimaryKey val msgId: Long,
    val seenAt: Long,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)

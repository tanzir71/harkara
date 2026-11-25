package io.github.tanzir71.harkara.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chat: a NIP-17 room (DM or group, keyed by its member set), the area channel or the Wi-Fi room.
 * Everything here stays on the phone, inside the SQLCipher database.
 */
@Entity(tableName = "chat_conversations", indices = [Index("lastAt")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    /** [io.github.tanzir71.harkara.chat.ConversationType] name. */
    val type: String,
    /** Sorted member pubkeys (hex), comma-separated, including ours; empty for public rooms. */
    val members: String,
    /** NIP-17 `subject`: the newest one names the room. */
    val subject: String?,
    val subjectAt: Long,
    /** Geohash-5 of an area channel. */
    val geohash: String?,
    /** Newest message, unix seconds. */
    val lastAt: Long,
    val lastText: String,
    val unread: Int,
    val createdAt: Long,
) {
    fun memberList(): List<String> = members.split(',').filter { it.isNotEmpty() }
}

/** A message in a chat: ours or someone else's, text, card, voice note or a local system line. */
@Entity(tableName = "chat_messages", indices = [Index("conversationId"), Index("createdAt")])
data class ChatMessageEntity(
    /** Rumor id (text, cards), voice-note id, signed event id (public rooms) or a local id (system lines). */
    @PrimaryKey val id: String,
    val conversationId: String,
    /** Author pubkey (hex). */
    val author: String,
    /** The `n` name the author sent with this message, if any. */
    val authorName: String?,
    /** Unix seconds (the rumor's own time, not the back-dated wrap's). */
    val createdAt: Long,
    /** [io.github.tanzir71.harkara.chat.MessageType] name. */
    val type: String,
    val text: String,
    /** The `card` tag as JSON, for card messages. */
    val card: String?,
    val voiceMime: String?,
    val voiceDurationMs: Long?,
    val replyTo: String?,
    val own: Boolean,
    /** Carrier it arrived by (incoming) — ours are computed from their wraps. */
    val carrier: String,
    /** Epoch millis this phone stored it. */
    val receivedAt: Long,
    /** Epoch millis we read it (incoming only). */
    val readAt: Long?,
)

/**
 * Every gift wrap (and public-room event) this phone sent, with the message it carries. The internet
 * relays' progress lives in the shared outbox row of the same id; [lanState] is the Wi-Fi room's.
 */
@Entity(tableName = "chat_wraps", indices = [Index("messageId"), Index("conversationId")])
data class ChatWrapEntity(
    @PrimaryKey val wrapId: String,
    val messageId: String?,
    val conversationId: String?,
    /** Recipient pubkey, or `*` for public rooms. */
    val recipient: String,
    val json: String,
    /** True for wraps that go to the Wi-Fi room only (never to relays). */
    val lanOnly: Boolean,
    val createdAt: Long,
    /** [io.github.tanzir71.harkara.ui.components.Delivery] name on the Wi-Fi room. */
    val lanState: String,
)

/** A member's delivered/read receipt for one of our messages. */
@Entity(tableName = "chat_receipts", primaryKeys = ["messageId", "member"], indices = [Index("conversationId")])
data class ChatReceiptEntity(
    val messageId: String,
    val conversationId: String,
    val member: String,
    /** `Delivered` or `Read`. */
    val status: String,
    val at: Long,
)

/** One part of a voice note (Base64 audio), kept until the note is complete and after, for playback. */
@Entity(tableName = "chat_voice_parts", primaryKeys = ["voiceId", "idx"])
data class VoicePartEntity(
    val voiceId: String,
    val idx: Int,
    val count: Int,
    val mime: String,
    val durationMs: Long,
    val sha256: String,
    val data: String,
    val receivedAt: Long,
)

/** People we chat with: the name we gave them (never published) and the name they send. No phone numbers. */
@Entity(tableName = "chat_contacts")
data class ContactEntity(
    @PrimaryKey val pubkey: String,
    val petName: String?,
    val theirName: String?,
    val updatedAt: Long,
)

/** A wrap and the states it has reached (relay outbox row joined with the Wi-Fi room state). */
data class WrapState(
    val wrapId: String,
    val messageId: String?,
    val recipient: String,
    val lanState: String,
    val relayState: String?,
    val relayCarrier: String?,
)

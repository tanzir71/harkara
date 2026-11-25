package io.github.tanzir71.harkara.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // Conversations
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM chat_conversations ORDER BY lastAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE chat_conversations SET lastAt = :at, lastText = :text, unread = unread + :unread WHERE id = :id AND lastAt <= :at")
    suspend fun bumpConversation(id: String, at: Long, text: String, unread: Int)

    @Query("UPDATE chat_conversations SET unread = unread + :unread WHERE id = :id")
    suspend fun addUnread(id: String, unread: Int)

    @Query("UPDATE chat_conversations SET subject = :subject, subjectAt = :at WHERE id = :id AND subjectAt <= :at")
    suspend fun setSubject(id: String, subject: String, at: Long)

    @Query("UPDATE chat_conversations SET unread = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    // Messages
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun message(id: String): ChatMessageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE id = :id)")
    suspend fun messageExists(id: String): Boolean

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, receivedAt ASC LIMIT :limit")
    fun observeMessages(conversationId: String, limit: Int = 500): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId AND own = 0 AND readAt IS NULL AND type != 'SYSTEM'")
    suspend fun unreadIncoming(conversationId: String): List<ChatMessageEntity>

    @Query("UPDATE chat_messages SET readAt = :at WHERE id IN (:ids)")
    suspend fun markRead(ids: List<String>, at: Long)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId AND createdAt < :beforeSeconds")
    suspend fun purgeBefore(conversationId: String, beforeSeconds: Long): Int

    // Wraps we sent
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWrap(wrap: ChatWrapEntity)

    /** Moves a wrap's Wi-Fi room state up (never down), atomically: only from one of [below]. */
    @Query("UPDATE chat_wraps SET lanState = :state WHERE wrapId = :wrapId AND lanState IN (:below)")
    suspend fun raiseLanState(wrapId: String, state: String, below: List<String>): Int

    @Query("SELECT * FROM chat_wraps WHERE wrapId = :wrapId")
    suspend fun wrap(wrapId: String): ChatWrapEntity?

    @Query("SELECT * FROM chat_wraps WHERE messageId = :messageId")
    suspend fun wrapsFor(messageId: String): List<ChatWrapEntity>

    @Query("SELECT * FROM chat_wraps WHERE lanState IN ('Saved', 'LeftPhone') AND createdAt >= :sinceMillis ORDER BY createdAt ASC")
    suspend fun lanPending(sinceMillis: Long): List<ChatWrapEntity>

    @Query(
        """SELECT w.wrapId AS wrapId, w.messageId AS messageId, w.recipient AS recipient, w.lanState AS lanState,
                  o.state AS relayState, o.carrier AS relayCarrier
           FROM chat_wraps w LEFT JOIN outbox o ON o.eventId = w.wrapId
           WHERE w.conversationId = :conversationId AND w.messageId IS NOT NULL""",
    )
    fun observeWrapStates(conversationId: String): Flow<List<WrapState>>

    // Receipts
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceipt(receipt: ChatReceiptEntity)

    @Query("SELECT * FROM chat_receipts WHERE messageId = :messageId AND member = :member")
    suspend fun receipt(messageId: String, member: String): ChatReceiptEntity?

    @Query("SELECT * FROM chat_receipts WHERE conversationId = :conversationId")
    fun observeReceipts(conversationId: String): Flow<List<ChatReceiptEntity>>

    // Voice parts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVoicePart(part: VoicePartEntity)

    @Query("SELECT * FROM chat_voice_parts WHERE voiceId = :voiceId ORDER BY idx ASC")
    suspend fun voiceParts(voiceId: String): List<VoicePartEntity>

    // Contacts
    @Query("SELECT * FROM chat_contacts")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM chat_contacts WHERE pubkey = :pubkey")
    suspend fun contact(pubkey: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(contact: ContactEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM mutes WHERE type = 'author' AND value = :pubkey)")
    suspend fun isBlocked(pubkey: String): Boolean
}

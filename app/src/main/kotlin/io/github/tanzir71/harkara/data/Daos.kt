package io.github.tanzir71.harkara.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: IdentityEntity)

    @Query("SELECT * FROM identities ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<IdentityEntity>>
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MessageEntity>>
}

@Dao
interface PostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: PostEntity)

    @Query("SELECT * FROM posts WHERE expiresAt > :now ORDER BY createdAt DESC")
    fun observeFresh(now: Long): Flow<List<PostEntity>>

    @Query("DELETE FROM posts WHERE expiresAt <= :now")
    suspend fun purgeExpired(now: Long): Int
}

@Dao
interface ReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ReactionEntity)

    @Query("SELECT * FROM reactions WHERE postId = :postId")
    fun observeForPost(postId: String): Flow<List<ReactionEntity>>
}

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: ChannelEntity)

    @Query("SELECT * FROM channels ORDER BY name")
    fun observeAll(): Flow<List<ChannelEntity>>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE deliveredAt IS NULL ORDER BY priority DESC, nextAttemptAt ASC")
    fun observePending(): Flow<List<OutboxEntity>>
}

@Dao
interface DedupeSeenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSeen(item: DedupeSeenEntity)

    @Query("DELETE FROM dedupe_seen WHERE seenAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingsEntity)

    @Query("SELECT * FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<SettingsEntity?>
}

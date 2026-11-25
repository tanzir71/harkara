package io.github.tanzir71.harkara.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** (created_at, id) pairs for NIP-77 reconciliation. */
data class IdTime(val id: String, val createdAt: Long)

/** Size/age view used by the storage budget. */
data class StoredRow(val id: String, val bytes: Int, val receivedAt: Long, val own: Boolean)

@Dao
interface EventDao {
    /** Returns the row id, or -1 when the event was already stored (dedupe by id). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): EventEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query(
        """SELECT * FROM events WHERE kind = 1 AND geohash5 IN (:cells)
           AND (expiresAt IS NULL OR expiresAt > :nowSeconds) ORDER BY createdAt DESC LIMIT :limit""",
    )
    fun observeFeed(cells: List<String>, nowSeconds: Long, limit: Int = 300): Flow<List<EventEntity>>

    @Query("SELECT id, createdAt FROM events WHERE kind = 1 AND geohash5 IN (:cells) AND createdAt >= :sinceSeconds")
    suspend fun feedIdTimes(cells: List<String>, sinceSeconds: Long): List<IdTime>

    @Query("SELECT * FROM events WHERE kind IN (7, 1984) AND target IN (:postIds)")
    fun observeReactions(postIds: List<String>): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind IN (7, 1984) AND target IN (:postIds)")
    suspend fun reactionsFor(postIds: List<String>): List<EventEntity>

    /** Task offers nearby, my own, and the ones I took a step on. */
    @Query(
        """SELECT * FROM events WHERE kind = 30402 AND (geohash5 IN (:cells) OR own = 1
           OR id IN (SELECT target FROM events WHERE kind = 3471 AND own = 1)) ORDER BY createdAt DESC LIMIT 200""",
    )
    fun observeTaskOffers(cells: List<String>): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind = 3471 AND target IN (:offerIds)")
    fun observeSteps(offerIds: List<String>): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind = 3471 AND target IN (:offerIds)")
    suspend fun stepsFor(offerIds: List<String>): List<EventEntity>

    @Query("SELECT * FROM events WHERE kind IN (:kinds) ORDER BY createdAt DESC LIMIT 500")
    fun observeKinds(kinds: List<Int>): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind IN (:kinds) ORDER BY createdAt DESC LIMIT 500")
    suspend fun byKinds(kinds: List<Int>): List<EventEntity>

    @Query("SELECT * FROM events WHERE kind = :kind ORDER BY createdAt DESC")
    suspend fun byKind(kind: Int): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE pubkey = :pubkey AND kind = :kind AND createdAt >= :sinceSeconds")
    suspend fun countSince(pubkey: String, kind: Int, sinceSeconds: Long): Int

    @Query("SELECT COUNT(*) FROM events WHERE own = 1 AND kind = :kind AND createdAt >= :sinceSeconds")
    suspend fun countOwnSince(kind: Int, sinceSeconds: Long): Int

    @Query("DELETE FROM events WHERE own = 0 AND expiresAt IS NOT NULL AND expiresAt <= :nowSeconds")
    suspend fun purgeExpired(nowSeconds: Long): Int

    @Query("SELECT id, bytes, receivedAt, own FROM events")
    suspend fun storedRows(): List<StoredRow>

    @Query("DELETE FROM events WHERE id IN (:ids) AND own = 0")
    suspend fun deleteOthers(ids: List<String>): Int

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM events WHERE own = 0")
    suspend fun othersBytes(): Long
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE eventId = :id")
    suspend fun get(id: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE state IN ('Saved', 'LeftPhone') AND nextAttemptAt <= :now ORDER BY priority DESC, createdAt ASC")
    suspend fun due(now: Long): List<OutboxEntity>

    /** Everything not yet acknowledged by a relay, ignoring relay backoff (mesh and node routes). */
    @Query("SELECT * FROM outbox WHERE state IN ('Saved', 'LeftPhone') ORDER BY priority DESC, createdAt ASC")
    suspend fun pending(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('Saved', 'LeftPhone')")
    fun observeWaiting(): Flow<Int>

    @Query("SELECT * FROM outbox")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("UPDATE outbox SET state = :state, carrier = :carrier WHERE eventId = :id AND (CASE state WHEN 'Saved' THEN 0 WHEN 'LeftPhone' THEN 1 WHEN 'ReachedNetwork' THEN 2 WHEN 'Delivered' THEN 3 WHEN 'Read' THEN 4 END) < (CASE :state WHEN 'Saved' THEN 0 WHEN 'LeftPhone' THEN 1 WHEN 'ReachedNetwork' THEN 2 WHEN 'Delivered' THEN 3 WHEN 'Read' THEN 4 END)")
    suspend fun setState(id: String, state: String, carrier: String?)

    @Query("UPDATE outbox SET attempts = attempts + 1, nextAttemptAt = :next, state = 'LeftPhone', carrier = :carrier WHERE eventId = :id AND state IN ('Saved', 'LeftPhone')")
    suspend fun recordAttempt(id: String, next: Long, carrier: String)
}

@Dao
interface RelayAckDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ack: RelayAckEntity)

    @Query("SELECT * FROM relay_acks WHERE eventId = :id")
    suspend fun forEvent(id: String): List<RelayAckEntity>
}

@Dao
interface MuteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(mute: MuteEntity)

    @Query("DELETE FROM mutes WHERE type = :type AND value = :value")
    suspend fun remove(type: String, value: String)

    @Query("SELECT * FROM mutes")
    fun observeAll(): Flow<List<MuteEntity>>
}

@Dao
interface LocalSecretDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(secret: LocalSecretEntity)

    @Query("SELECT value FROM local_secrets WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM local_secrets WHERE key LIKE :prefix || '%'")
    fun observePrefix(prefix: String): Flow<List<LocalSecretEntity>>
}

@Dao
interface IouDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(iou: IouEntity)

    @Query("SELECT * FROM ious WHERE id = :id")
    suspend fun get(id: String): IouEntity?

    @Query("SELECT * FROM ious ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IouEntity>>

    @Query("SELECT * FROM ious ORDER BY createdAt DESC")
    suspend fun all(): List<IouEntity>
}

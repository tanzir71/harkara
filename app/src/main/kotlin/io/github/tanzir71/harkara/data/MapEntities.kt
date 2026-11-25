package io.github.tanzir71.harkara.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The newest version of every pin this phone knows (replaceable: one row per `a` address). [json] is the
 * signed event (private and public pins, so they can be re-sent unchanged) or the rumor (shared pins).
 */
@Entity(tableName = "pins", indices = [Index("owner"), Index("geohash5")])
data class PinEntity(
    @PrimaryKey val address: String,
    val id: String,
    val owner: String,
    val visibility: String,
    val title: String,
    val note: String,
    val lat: Double,
    val lng: Double,
    val color: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val deleted: Boolean,
    /** `p` recipients of a shared pin, newline-separated. */
    val recipients: String,
    val eventId: String,
    val geohash5: String,
    val json: String,
    val own: Boolean,
    val updatedAt: Long,
)

/** The latest "still there?" answer per key per pin. */
@Entity(tableName = "pin_votes", primaryKeys = ["address", "voter"])
data class PinVoteEntity(
    val address: String,
    val voter: String,
    val yes: Boolean,
    val createdAt: Long,
    val json: String,
    val own: Boolean,
)

/** Check-ins, trip plans and overdue alerts we sent or received (opened from gift wraps). */
@Entity(tableName = "check_ins", indices = [Index("createdAt")])
data class CheckInEntity(
    @PrimaryKey val id: String,
    val author: String,
    val status: String,
    val place: String,
    val geohash: String?,
    val tripId: String?,
    val destination: String?,
    val due: Long?,
    val note: String,
    val createdAt: Long,
    val recipients: String,
    val own: Boolean,
    val receivedAt: Long,
    val name: String? = null,
)

/** The user's own trip plans ([json] = core TripPlan). */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val json: String,
    val status: String,
    val due: Long,
    val updatedAt: Long,
)

@Dao
interface PinDao {
    @Query("SELECT * FROM pins WHERE address = :address")
    suspend fun get(address: String): PinEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pin: PinEntity)

    @Query("SELECT * FROM pins WHERE deleted = 0 ORDER BY createdAt DESC LIMIT 2000")
    fun observeAll(): Flow<List<PinEntity>>

    @Query("SELECT * FROM pins WHERE own = 1 AND deleted = 0 ORDER BY createdAt DESC")
    suspend fun mine(): List<PinEntity>

    @Query("SELECT * FROM pin_votes WHERE address = :address AND voter = :voter")
    suspend fun vote(address: String, voter: String): PinVoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVote(vote: PinVoteEntity)

    @Query("SELECT * FROM pin_votes")
    fun observeVotes(): Flow<List<PinVoteEntity>>

    @Query("DELETE FROM pins WHERE own = 0 AND expiresAt IS NOT NULL AND expiresAt <= :nowSeconds")
    suspend fun purgeExpired(nowSeconds: Long): Int
}

@Dao
interface CheckInDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(checkIn: CheckInEntity): Long

    @Query("SELECT * FROM check_ins WHERE createdAt >= :sinceSeconds ORDER BY createdAt DESC")
    fun observeSince(sinceSeconds: Long): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE own = 1 AND createdAt >= :sinceSeconds ORDER BY createdAt DESC")
    suspend fun ownSince(sinceSeconds: Long): List<CheckInEntity>
}

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: String): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' ORDER BY due ASC LIMIT 1")
    suspend fun active(): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' ORDER BY due ASC LIMIT 1")
    fun observeActive(): Flow<TripEntity?>
}

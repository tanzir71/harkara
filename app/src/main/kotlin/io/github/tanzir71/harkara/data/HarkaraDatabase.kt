package io.github.tanzir71.harkara.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Room database containing all offline Harkara state. */
@Database(
    entities = [
        IdentityEntity::class,
        MessageEntity::class,
        PostEntity::class,
        ReactionEntity::class,
        ChannelEntity::class,
        OutboxEntity::class,
        DedupeSeenEntity::class,
        SettingsEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class HarkaraDatabase : RoomDatabase() {
    /** DAO for peer identities. */
    abstract fun identityDao(): IdentityDao

    /** DAO for messages. */
    abstract fun messageDao(): MessageDao

    /** DAO for posts. */
    abstract fun postDao(): PostDao

    /** DAO for reactions. */
    abstract fun reactionDao(): ReactionDao

    /** DAO for channels. */
    abstract fun channelDao(): ChannelDao

    /** DAO for outbox rows. */
    abstract fun outboxDao(): OutboxDao

    /** DAO for dedupe state. */
    abstract fun dedupeSeenDao(): DedupeSeenDao

    /** DAO for settings. */
    abstract fun settingsDao(): SettingsDao

    companion object {
        /** Creates the production Room database. */
        fun create(context: Context): HarkaraDatabase =
            Room.databaseBuilder(context, HarkaraDatabase::class.java, "Harkara.db").build()
    }
}

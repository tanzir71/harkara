package io.github.tanzir71.harkara.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/** Room database for everything Harkara keeps on the phone; encrypted with SQLCipher on devices. */
@Database(
    entities = [EventEntity::class, OutboxEntity::class, RelayAckEntity::class, MuteEntity::class, LocalSecretEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class HarkaraDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun outboxDao(): OutboxDao
    abstract fun relayAckDao(): RelayAckDao
    abstract fun muteDao(): MuteDao
    abstract fun localSecretDao(): LocalSecretDao

    companion object {
        const val NAME = "harkara.db"

        /**
         * Production database on SQLCipher. The 32-byte key is random, kept in no-backup storage and erased
         * by panic wipe together with the database.
         */
        fun create(context: Context): HarkaraDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, HarkaraDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(databaseKey(context)))
                .fallbackToDestructiveMigration(true)
                .build()
        }

        /** Plain in-memory database for JVM tests (SQLCipher's native library can't load there). */
        fun inMemory(context: Context): HarkaraDatabase =
            Room.inMemoryDatabaseBuilder(context, HarkaraDatabase::class.java).allowMainThreadQueries().build()

        private fun databaseKey(context: Context): ByteArray {
            val file = File(context.noBackupFilesDir, "db.key")
            if (file.exists() && file.length() == 32L) return file.readBytes()
            // A new key means the old encrypted file is unreadable: start fresh.
            context.deleteDatabase(NAME)
            return ByteArray(32).also { SecureRandom().nextBytes(it); file.writeBytes(it) }
        }
    }
}

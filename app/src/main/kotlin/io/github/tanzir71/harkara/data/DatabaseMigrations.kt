package io.github.tanzir71.harkara.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive upgrades from both schema-5 branches (chats and maps) to their schema-6 union. */
object DatabaseMigrations {
    /** Register with `Room.databaseBuilder(...).addMigrations(*DatabaseMigrations.ALL)`. */
    val ALL: Array<Migration> = (1..5).map { version ->
        object : Migration(version, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val outboxColumns = columns(db, "outbox")
                if (outboxColumns.isNotEmpty() && "eventId" !in outboxColumns) {
                    check(outboxColumns.containsAll(listOf("id", "msgId", "packetType", "payload", "attemptCount", "deliveredAt"))) {
                        "Unrecognized legacy outbox; refusing to overwrite it"
                    }
                    // Version 1 used unsigned BLOB packets, not Nostr events. Keep them, never resend them.
                    db.execSQL("ALTER TABLE `outbox` RENAME TO `legacy_v1_outbox`")
                }
                TABLES.forEach(db::execSQL)
                // Early and late M9 builds both called themselves v5; only the latter included the name.
                if ("name" !in columns(db, "check_ins")) {
                    db.execSQL("ALTER TABLE `check_ins` ADD COLUMN `name` TEXT")
                }
                INDICES.forEach(db::execSQL)
            }
        }
    }.toTypedArray()

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }

    // v2 (M4), v3 (M7), v4 (M8) and both v5 branches share identical existing column layouts.
    // Keep this SQL independent of generated Room code so it also works with SQLCipher on devices.
    private val TABLES = listOf(
        """CREATE TABLE IF NOT EXISTS `events` (
            `id` TEXT NOT NULL, `pubkey` TEXT NOT NULL, `kind` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL, `json` TEXT NOT NULL, `geohash` TEXT,
            `geohash5` TEXT, `category` TEXT, `target` TEXT, `expiresAt` INTEGER,
            `receivedAt` INTEGER NOT NULL, `bytes` INTEGER NOT NULL,
            `own` INTEGER NOT NULL, `carrier` TEXT NOT NULL, PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `outbox` (
            `eventId` TEXT NOT NULL, `json` TEXT NOT NULL, `priority` INTEGER NOT NULL,
            `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `state` TEXT NOT NULL,
            `carrier` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`))""",
        """CREATE TABLE IF NOT EXISTS `relay_acks` (
            `eventId` TEXT NOT NULL, `relayUrl` TEXT NOT NULL, `accepted` INTEGER NOT NULL,
            `message` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`eventId`, `relayUrl`))""",
        """CREATE TABLE IF NOT EXISTS `mutes` (
            `type` TEXT NOT NULL, `value` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`type`, `value`))""",
        """CREATE TABLE IF NOT EXISTS `local_secrets` (
            `key` TEXT NOT NULL, `value` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`key`))""",
        """CREATE TABLE IF NOT EXISTS `ious` (
            `id` TEXT NOT NULL, `bundleJson` TEXT NOT NULL, `counterpartyPubkey` TEXT,
            `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `chat_conversations` (
            `id` TEXT NOT NULL, `type` TEXT NOT NULL, `members` TEXT NOT NULL, `subject` TEXT,
            `subjectAt` INTEGER NOT NULL, `geohash` TEXT, `lastAt` INTEGER NOT NULL,
            `lastText` TEXT NOT NULL, `unread` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `chat_messages` (
            `id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `author` TEXT NOT NULL,
            `authorName` TEXT, `createdAt` INTEGER NOT NULL, `type` TEXT NOT NULL,
            `text` TEXT NOT NULL, `card` TEXT, `voiceMime` TEXT, `voiceDurationMs` INTEGER,
            `replyTo` TEXT, `own` INTEGER NOT NULL, `carrier` TEXT NOT NULL,
            `receivedAt` INTEGER NOT NULL, `readAt` INTEGER, PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `chat_wraps` (
            `wrapId` TEXT NOT NULL, `messageId` TEXT, `conversationId` TEXT, `recipient` TEXT NOT NULL,
            `json` TEXT NOT NULL, `lanOnly` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
            `lanState` TEXT NOT NULL, PRIMARY KEY(`wrapId`))""",
        """CREATE TABLE IF NOT EXISTS `chat_receipts` (
            `messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `member` TEXT NOT NULL,
            `status` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `member`))""",
        """CREATE TABLE IF NOT EXISTS `chat_voice_parts` (
            `voiceId` TEXT NOT NULL, `idx` INTEGER NOT NULL, `count` INTEGER NOT NULL,
            `mime` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `sha256` TEXT NOT NULL,
            `data` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, PRIMARY KEY(`voiceId`, `idx`))""",
        """CREATE TABLE IF NOT EXISTS `chat_contacts` (
            `pubkey` TEXT NOT NULL, `petName` TEXT, `theirName` TEXT,
            `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`pubkey`))""",
        """CREATE TABLE IF NOT EXISTS `pins` (
            `address` TEXT NOT NULL, `id` TEXT NOT NULL, `owner` TEXT NOT NULL,
            `visibility` TEXT NOT NULL, `title` TEXT NOT NULL, `note` TEXT NOT NULL,
            `lat` REAL NOT NULL, `lng` REAL NOT NULL, `color` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL, `expiresAt` INTEGER, `deleted` INTEGER NOT NULL,
            `recipients` TEXT NOT NULL, `eventId` TEXT NOT NULL, `geohash5` TEXT NOT NULL,
            `json` TEXT NOT NULL, `own` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`address`))""",
        """CREATE TABLE IF NOT EXISTS `pin_votes` (
            `address` TEXT NOT NULL, `voter` TEXT NOT NULL, `yes` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL, `json` TEXT NOT NULL, `own` INTEGER NOT NULL,
            PRIMARY KEY(`address`, `voter`))""",
        """CREATE TABLE IF NOT EXISTS `check_ins` (
            `id` TEXT NOT NULL, `author` TEXT NOT NULL, `status` TEXT NOT NULL, `place` TEXT NOT NULL,
            `geohash` TEXT, `tripId` TEXT, `destination` TEXT, `due` INTEGER, `note` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL, `recipients` TEXT NOT NULL, `own` INTEGER NOT NULL,
            `receivedAt` INTEGER NOT NULL, `name` TEXT, PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `trips` (
            `id` TEXT NOT NULL, `json` TEXT NOT NULL, `status` TEXT NOT NULL,
            `due` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
    )

    private val INDICES = listOf(
        "CREATE INDEX IF NOT EXISTS `index_events_kind` ON `events` (`kind`)",
        "CREATE INDEX IF NOT EXISTS `index_events_geohash5` ON `events` (`geohash5`)",
        "CREATE INDEX IF NOT EXISTS `index_events_target` ON `events` (`target`)",
        "CREATE INDEX IF NOT EXISTS `index_events_receivedAt` ON `events` (`receivedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_events_pubkey` ON `events` (`pubkey`)",
        "CREATE INDEX IF NOT EXISTS `index_outbox_state` ON `outbox` (`state`)",
        "CREATE INDEX IF NOT EXISTS `index_ious_updatedAt` ON `ious` (`updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_chat_conversations_lastAt` ON `chat_conversations` (`lastAt`)",
        "CREATE INDEX IF NOT EXISTS `index_chat_messages_conversationId` ON `chat_messages` (`conversationId`)",
        "CREATE INDEX IF NOT EXISTS `index_chat_messages_createdAt` ON `chat_messages` (`createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_chat_wraps_messageId` ON `chat_wraps` (`messageId`)",
        "CREATE INDEX IF NOT EXISTS `index_chat_wraps_conversationId` ON `chat_wraps` (`conversationId`)",
        "CREATE INDEX IF NOT EXISTS `index_chat_receipts_conversationId` ON `chat_receipts` (`conversationId`)",
        "CREATE INDEX IF NOT EXISTS `index_pins_owner` ON `pins` (`owner`)",
        "CREATE INDEX IF NOT EXISTS `index_pins_geohash5` ON `pins` (`geohash5`)",
        "CREATE INDEX IF NOT EXISTS `index_check_ins_createdAt` ON `check_ins` (`createdAt`)",
    )
}

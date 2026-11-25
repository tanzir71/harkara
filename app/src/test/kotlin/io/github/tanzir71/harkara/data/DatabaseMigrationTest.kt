package io.github.tanzir71.harkara.data

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Historical DDL fixtures are independent of DatabaseMigrations and generated Room schema code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() { names.forEach(context::deleteDatabase) }

    @Test
    fun `v1 preserves unsigned legacy state and archives incompatible packet outbox`() =
        migrate(1, LEGACY)

    @Test
    fun `M4 v2 preserves events outbox relay acks and mutes`() =
        migrate(2, BASE)

    @Test
    fun `M7 v3 preserves local handoff secrets`() =
        migrate(3, BASE + SECRETS)

    @Test
    fun `premerge M5 v3 preserves chats without task or IOU tables`() =
        migrate(3, BASE + CHATS)

    @Test
    fun `M8 v4 preserves IOUs and prior state`() =
        migrate(4, BASE + SECRETS + IOUS)

    @Test
    fun `main v5 preserves all chats IOUs and pending outbox rows`() =
        migrate(5, BASE + SECRETS + IOUS + CHATS)

    @Test
    fun `early M9 v5 preserves maps and adds nullable checkin name`() =
        migrate(5, BASE + SECRETS + IOUS + MAPS, earlyCheckIns = true)

    @Test
    fun `late M9 v5 preserves checkin names and all map state`() =
        migrate(5, BASE + SECRETS + IOUS + MAPS)

    @Test
    fun `v5 containing both branches preserves every table`() =
        migrate(5, BASE + SECRETS + IOUS + CHATS + MAPS)

    private data class Snapshot(val table: String, val columns: List<String>, val rows: List<List<String?>>)

    private fun migrate(version: Int, tables: Map<String, String>, earlyCheckIns: Boolean = false) {
        val name = "migration-$version-${names.size}.db".also { names += it }
        val snapshots = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            tables.forEach { (table, definition) ->
                val columns = if (earlyCheckIns && table == "check_ins") definition.replace(", name TEXT", "") else definition
                old.execSQL("CREATE TABLE `$table` ($columns)")
            }
            if (version >= 2) {
                HISTORICAL_INDICES.filterKeys { it in tables }.values.flatten().forEach { old.execSQL(it) }
            }
            tables.keys.forEach { insertSentinel(old, it) }
            // A stale Room identity hash must be replaced by the migration, not confused with schema 6.
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42, 'historical-schema-$version')")
            old.version = version
            tables.keys.map { table ->
                old.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
                    Snapshot(table, cursor.columnNames.toList(), rows(cursor))
                }
            }
        }

        fun open() = Room.databaseBuilder(context, HarkaraDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        val room = open()
        try {
            // Opening through the generated implementation executes Room's complete schema validation.
            val db = room.openHelper.writableDatabase
            assertEquals(6, db.version)
            snapshots.forEach { snapshot ->
                val table = if (version == 1 && snapshot.table == "outbox") "legacy_v1_outbox" else snapshot.table
                val projection = snapshot.columns.joinToString(",") { "`$it`" }
                db.query("SELECT $projection FROM `$table`").use {
                    assertEquals("Every old field must survive in $table", snapshot.rows, rows(it))
                }
            }
            val expectedTables = BASE.keys + SECRETS.keys + IOUS.keys + CHATS.keys + MAPS.keys
            val actualTables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(actualTables.containsAll(expectedTables))
            db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
                assertTrue(it.moveToFirst())
                assertFalse(it.getString(0).startsWith("historical-schema-"))
            }
            if (earlyCheckIns) {
                db.query("SELECT name FROM check_ins").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
            }
            if (version == 1) {
                db.query("SELECT COUNT(*) FROM outbox").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            // Newly created tables must be writable too; keep one extra row across a second real open.
            db.execSQL("INSERT INTO trips (id, json, status, due, updatedAt) VALUES ('new-trip', '{\"keep\":true}', 'ACTIVE', 99, 100)")
        } finally {
            room.close()
        }
        val reopened = open()
        try {
            reopened.openHelper.writableDatabase.query("SELECT json FROM trips WHERE id = 'new-trip'").use {
                assertTrue(it.moveToFirst())
                assertEquals("{\"keep\":true}", it.getString(0))
            }
        } finally {
            reopened.close()
        }
    }

    private fun insertSentinel(db: SQLiteDatabase, table: String) {
        val values = ContentValues()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                when (type) {
                    "INTEGER" -> values.put(column, 1_764_000_123L + cursor.position)
                    "REAL" -> values.put(column, 23.7465 + cursor.position)
                    "BLOB" -> values.put(column, byteArrayOf(0, 1, -1, 127, -128, 0))
                    else -> values.put(column, "$table.$column: keep this exact private value")
                }
                if (table == "outbox" && column == "state") values.put(column, "Saved")
                if (column == "own") values.put(column, 1)
                if (table == "ious" && column == "bundleJson") values.put(column, "{\"signed-iou\":\"retain\"}")
                if (table == "chat_messages" && column == "text") values.put(column, "Private message, never lose this")
            }
        }
        db.insertOrThrow(table, null, values)
    }

    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
            when (cursor.getType(column)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(column).joinToString(",")
                else -> cursor.getString(column)
            }
        })
    }

    companion object {
        // Definitions transcribed from 8fc0522 (v2), bd72e3a (v3), cc3872b (v4),
        // 93856c7 (chat v5), 8b10f81/4fd7017 (map v5), and 445646b (legacy v1).
        private val BASE = linkedMapOf(
            "events" to "id TEXT NOT NULL PRIMARY KEY, pubkey TEXT NOT NULL, kind INTEGER NOT NULL, createdAt INTEGER NOT NULL, json TEXT NOT NULL, geohash TEXT, geohash5 TEXT, category TEXT, target TEXT, expiresAt INTEGER, receivedAt INTEGER NOT NULL, bytes INTEGER NOT NULL, own INTEGER NOT NULL, carrier TEXT NOT NULL",
            "outbox" to "eventId TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL, priority INTEGER NOT NULL, attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, state TEXT NOT NULL, carrier TEXT, createdAt INTEGER NOT NULL",
            "relay_acks" to "eventId TEXT NOT NULL, relayUrl TEXT NOT NULL, accepted INTEGER NOT NULL, message TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(eventId, relayUrl)",
            "mutes" to "type TEXT NOT NULL, value TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(type, value)",
        )
        private val SECRETS = mapOf(
            "local_secrets" to "`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, createdAt INTEGER NOT NULL",
        )
        private val IOUS = mapOf(
            "ious" to "id TEXT NOT NULL PRIMARY KEY, bundleJson TEXT NOT NULL, counterpartyPubkey TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL",
        )
        private val CHATS = linkedMapOf(
            "chat_conversations" to "id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, members TEXT NOT NULL, subject TEXT, subjectAt INTEGER NOT NULL, geohash TEXT, lastAt INTEGER NOT NULL, lastText TEXT NOT NULL, unread INTEGER NOT NULL, createdAt INTEGER NOT NULL",
            "chat_messages" to "id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, author TEXT NOT NULL, authorName TEXT, createdAt INTEGER NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL, card TEXT, voiceMime TEXT, voiceDurationMs INTEGER, replyTo TEXT, own INTEGER NOT NULL, carrier TEXT NOT NULL, receivedAt INTEGER NOT NULL, readAt INTEGER",
            "chat_wraps" to "wrapId TEXT NOT NULL PRIMARY KEY, messageId TEXT, conversationId TEXT, recipient TEXT NOT NULL, json TEXT NOT NULL, lanOnly INTEGER NOT NULL, createdAt INTEGER NOT NULL, lanState TEXT NOT NULL",
            "chat_receipts" to "messageId TEXT NOT NULL, conversationId TEXT NOT NULL, member TEXT NOT NULL, status TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(messageId, member)",
            "chat_voice_parts" to "voiceId TEXT NOT NULL, idx INTEGER NOT NULL, count INTEGER NOT NULL, mime TEXT NOT NULL, durationMs INTEGER NOT NULL, sha256 TEXT NOT NULL, data TEXT NOT NULL, receivedAt INTEGER NOT NULL, PRIMARY KEY(voiceId, idx)",
            "chat_contacts" to "pubkey TEXT NOT NULL PRIMARY KEY, petName TEXT, theirName TEXT, updatedAt INTEGER NOT NULL",
        )
        private val MAPS = linkedMapOf(
            "pins" to "address TEXT NOT NULL PRIMARY KEY, id TEXT NOT NULL, owner TEXT NOT NULL, visibility TEXT NOT NULL, title TEXT NOT NULL, note TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, color TEXT NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER, deleted INTEGER NOT NULL, recipients TEXT NOT NULL, eventId TEXT NOT NULL, geohash5 TEXT NOT NULL, json TEXT NOT NULL, own INTEGER NOT NULL, updatedAt INTEGER NOT NULL",
            "pin_votes" to "address TEXT NOT NULL, voter TEXT NOT NULL, yes INTEGER NOT NULL, createdAt INTEGER NOT NULL, json TEXT NOT NULL, own INTEGER NOT NULL, PRIMARY KEY(address, voter)",
            "check_ins" to "id TEXT NOT NULL PRIMARY KEY, author TEXT NOT NULL, status TEXT NOT NULL, place TEXT NOT NULL, geohash TEXT, tripId TEXT, destination TEXT, due INTEGER, note TEXT NOT NULL, createdAt INTEGER NOT NULL, recipients TEXT NOT NULL, own INTEGER NOT NULL, receivedAt INTEGER NOT NULL, name TEXT",
            "trips" to "id TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL, status TEXT NOT NULL, due INTEGER NOT NULL, updatedAt INTEGER NOT NULL",
        )
        private val LEGACY = linkedMapOf(
            "identities" to "nearId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, ed25519Public BLOB NOT NULL, x25519Public BLOB NOT NULL, lastSeenAt INTEGER NOT NULL, optedIntoLocation INTEGER NOT NULL",
            "messages" to "msgId INTEGER NOT NULL PRIMARY KEY, senderId TEXT NOT NULL, channel TEXT, recipientId TEXT, body TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL",
            "posts" to "postId TEXT NOT NULL PRIMARY KEY, author TEXT NOT NULL, displayName TEXT NOT NULL, text TEXT NOT NULL, geohash TEXT NOT NULL, kind TEXT NOT NULL, imageRef TEXT, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, signature BLOB NOT NULL",
            "reactions" to "postId TEXT NOT NULL, author TEXT NOT NULL, kind TEXT NOT NULL, count INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(postId, author, kind)",
            "channels" to "name TEXT NOT NULL PRIMARY KEY, joinedAt INTEGER NOT NULL, lastReadAt INTEGER NOT NULL",
            "outbox" to "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, msgId INTEGER NOT NULL, packetType INTEGER NOT NULL, priority INTEGER NOT NULL, payload BLOB NOT NULL, attemptCount INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, deliveredAt INTEGER",
            "dedupe_seen" to "msgId INTEGER NOT NULL PRIMARY KEY, seenAt INTEGER NOT NULL",
            "settings" to "`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL",
        )
        private val HISTORICAL_INDICES = mapOf(
            "events" to listOf("kind", "geohash5", "target", "receivedAt", "pubkey"),
            "outbox" to listOf("state"),
            "ious" to listOf("updatedAt"),
            "chat_conversations" to listOf("lastAt"),
            "chat_messages" to listOf("conversationId", "createdAt"),
            "chat_wraps" to listOf("messageId", "conversationId"),
            "chat_receipts" to listOf("conversationId"),
            "pins" to listOf("owner", "geohash5"),
            "check_ins" to listOf("createdAt"),
        ).mapValues { (table, columns) -> columns.map { "CREATE INDEX index_${table}_$it ON `$table` (`$it`)" } }
    }
}

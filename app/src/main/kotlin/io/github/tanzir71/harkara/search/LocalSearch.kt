package io.github.tanzir71.harkara.search

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.data.HarkaraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** A rebuildable local FTS index. Private pins never leave the encrypted application database. */
class LocalSearch(private val db: HarkaraDatabase, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    data class Result(val id: String, val pin: Boolean, val title: String, val text: String, val position: LatLng?)

    suspend fun search(text: String): List<Result> = withContext(Dispatchers.IO) {
        val terms = Regex("[\\p{L}\\p{M}\\p{N}]+")
            .findAll(Normalizer.normalize(text.take(256), Normalizer.Form.NFKC))
            .map { it.value.take(64) }.take(6).toList()
        if (terms.isEmpty()) return@withContext emptyList()
        val query = terms.joinToString(" ") { "\"$it\"*" }
        val sql = db.openHelper.writableDatabase
        db.runInTransaction {
            sql.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS local_place_search USING fts4(ref, kind, title, body, lat, lng, area, notindexed=ref, notindexed=kind, notindexed=lat, notindexed=lng, notindexed=area, tokenize=unicode61)")
            sql.execSQL("DELETE FROM local_place_search")
            sql.execSQL("INSERT INTO local_place_search(ref, kind, title, body, lat, lng) SELECT address, 'pin', title, note, lat, lng FROM pins WHERE deleted = 0 AND (expiresAt IS NULL OR expiresAt > ?) ORDER BY createdAt DESC LIMIT 5000", arrayOf(now()))
            sql.query("SELECT id, json, geohash FROM events WHERE kind = 1 AND (expiresAt IS NULL OR expiresAt > ?) ORDER BY createdAt DESC LIMIT 5000", arrayOf(now())).use { cursor ->
                sql.compileStatement("INSERT INTO local_place_search(ref, kind, title, body, area) VALUES (?, 'post', '', ?, ?)").use { statement ->
                    while (cursor.moveToNext()) {
                        val body = runCatching { Event.fromJson(cursor.getString(1)).content }.getOrNull() ?: continue
                        statement.clearBindings()
                        statement.bindString(1, cursor.getString(0))
                        statement.bindString(2, body)
                        if (!cursor.isNull(2)) statement.bindString(3, cursor.getString(2))
                        statement.executeInsert()
                    }
                }
            }
        }
        sql.query("SELECT ref, kind, title, body, lat, lng, area FROM local_place_search WHERE local_place_search MATCH ? LIMIT 50", arrayOf(query)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val pin = cursor.getString(1) == "pin"
                    val body = cursor.getString(3).orEmpty()
                    val area = cursor.getString(6)
                    val position = if (pin) LatLng(cursor.getDouble(4), cursor.getDouble(5))
                        else area?.takeIf(GeoHash::isValid)?.let(GeoMath::decode)
                    add(Result(cursor.getString(0), pin, cursor.getString(2).orEmpty(), body, position))
                }
            }
        }
    }
}

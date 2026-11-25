package io.github.tanzir71.harkara.data

import androidx.room.Dao
import androidx.room.Query

/**
 * Prefix lookups for SMS payloads (M10): an SMS carries 8-byte references to keys and events this phone
 * already holds. Blocking on purpose: SMS decoding runs on a background dispatcher.
 */
@Dao
interface LookupDao {
    @Query("SELECT DISTINCT pubkey FROM events WHERE pubkey LIKE :prefix || '%' LIMIT 16")
    fun pubkeysWithPrefix(prefix: String): List<String>

    @Query("SELECT id FROM events WHERE id LIKE :prefix || '%' LIMIT 16")
    fun idsWithPrefix(prefix: String): List<String>

    @Query("SELECT bundleJson FROM ious")
    fun iouBundles(): List<String>
}

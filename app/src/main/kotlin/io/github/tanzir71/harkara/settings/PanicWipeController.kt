package io.github.tanzir71.harkara.settings

import android.content.Context
import io.github.tanzir71.harkara.data.HarkaraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Erases everything Harkara keeps on the phone: database, settings, files and caches. */
object PanicWipeController {
    /** Clears Room tables only (kept for callers that hold just the database). */
    suspend fun panicWipe(database: HarkaraDatabase) = withContext(Dispatchers.IO) {
        database.clearAllTables()
    }

    /** Terminal wipe. Callers must quiesce producers first and must never reuse this database. */
    suspend fun wipeEverything(
        context: Context,
        database: HarkaraDatabase?,
        settings: AppSettings,
        forgetIdentity: () -> Unit = {},
    ) = withContext(Dispatchers.IO + NonCancellable) {
        // Closing the SQLCipher handle must precede deleting its key, including on a cancelled UI call.
        database?.close()
        listOf(HarkaraDatabase.NAME, "Harkara.db").forEach { name ->
            check(context.deleteDatabase(name) || !context.getDatabasePath(name).exists()) { "Could not erase $name" }
        }
        forgetIdentity()
        listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir).forEach { dir ->
            dir.listFiles()?.forEach { file -> check(file.deleteRecursively()) { "Could not erase private data" } }
        }
        settings.clear()
    }
}

package io.github.tanzir71.harkara.settings

import android.content.Context
import io.github.tanzir71.harkara.data.HarkaraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Erases everything Harkara keeps on the phone: database, settings, files and caches. */
object PanicWipeController {
    /** Clears Room tables only (kept for callers that hold just the database). */
    suspend fun panicWipe(database: HarkaraDatabase) = withContext(Dispatchers.IO) {
        database.clearAllTables()
    }

    /** Full wipe, off the main thread. After this the app starts from onboarding again. */
    suspend fun wipeEverything(context: Context, database: HarkaraDatabase, settings: AppSettings) = withContext(Dispatchers.IO) {
        database.clearAllTables()
        listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir).forEach { dir ->
            dir.listFiles()?.forEach(File::deleteRecursively)
        }
        settings.clear()
    }
}

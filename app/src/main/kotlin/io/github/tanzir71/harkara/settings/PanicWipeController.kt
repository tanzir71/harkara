package io.github.tanzir71.harkara.settings

import io.github.tanzir71.harkara.data.HarkaraDatabase

/** Clears local app state for panic wipe. */
object PanicWipeController {
    /** Clears Room tables and locally held sensitive state. */
    suspend fun panicWipe(database: HarkaraDatabase) {
        database.clearAllTables()
    }
}

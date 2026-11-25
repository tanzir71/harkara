package io.github.tanzir71.harkara

import android.app.Application
import android.content.Context
import io.github.tanzir71.harkara.connectivity.ConnectivityMonitor
import io.github.tanzir71.harkara.connectivity.StaticConnectivityMonitor
import io.github.tanzir71.harkara.crash.CrashReporter
import io.github.tanzir71.harkara.data.DhakaAreas
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.identity.FileSeedStore
import io.github.tanzir71.harkara.identity.IdentityManager
import io.github.tanzir71.harkara.settings.AppSettings
import io.github.tanzir71.harkara.settings.PanicWipeController
import io.github.tanzir71.harkara.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Application root that owns manually wired dependencies. */
class HarkaraApplication : Application() {
    /** Lazily created app container. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.crashReporter.install()
    }
}

/** Manual dependency container. No Firebase, no Play Services. */
class AppContainer(private val context: Context) {
    val database: HarkaraDatabase by lazy { HarkaraDatabase.create(context) }
    val settings: AppSettings = AppSettings(context)
    val connectivity: ConnectivityMonitor = StaticConnectivityMonitor()
    val crashReporter = CrashReporter(context, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    val updateChecker = UpdateChecker(settings, BuildConfig.VERSION_CODE)
    val areas: DhakaAreas by lazy { DhakaAreas.load(context) }
    val identity: IdentityManager by lazy { IdentityManager(FileSeedStore(seedFile(context))) }

    suspend fun panicWipe() {
        withContext(Dispatchers.IO) { identity.wipe() }
        PanicWipeController.wipeEverything(context, database, settings)
    }

    companion object {
        /** App-private, excluded from backups, and inside a directory the panic wipe empties. */
        fun seedFile(context: Context): File = File(context.noBackupFilesDir, "identity/root-seed")
    }
}

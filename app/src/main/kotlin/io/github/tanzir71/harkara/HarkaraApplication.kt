package io.github.tanzir71.harkara

import android.app.Application
import io.github.tanzir71.harkara.data.HarkaraDatabase

/** Application root that owns manually wired dependencies. */
class HarkaraApplication : Application() {
    /** Lazily created app container. */
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Manual dependency container for Android adapters. */
class AppContainer(application: Application) {
    /** Room database backing local offline state. */
    val database: HarkaraDatabase = HarkaraDatabase.create(application)
}

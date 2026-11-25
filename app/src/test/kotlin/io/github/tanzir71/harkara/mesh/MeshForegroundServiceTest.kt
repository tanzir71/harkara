package io.github.tanzir71.harkara.mesh

import android.app.Service
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.HarkaraApplication
import kotlinx.coroutines.cancel

class OffgridServiceTestApplication : HarkaraApplication() {
    override val container by lazy { AppContainer(this, inMemoryDatabase = true) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OffgridServiceTestApplication::class)
class MeshForegroundServiceTest {
    @Test
    fun `service never restarts user radios after process death`() {
        val controller = Robolectric.buildService(MeshForegroundService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))

        controller.destroy()
        val container = (service.application as HarkaraApplication).container
        container.scope.cancel()
        container.database.close()
    }
}

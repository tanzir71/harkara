package io.github.tanzir71.harkara.mesh

import android.app.Service
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeshForegroundServiceTest {
    @Test
    fun `service start command is sticky for long lived mesh`() {
        val controller = Robolectric.buildService(MeshForegroundService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))

        controller.destroy()
    }
}

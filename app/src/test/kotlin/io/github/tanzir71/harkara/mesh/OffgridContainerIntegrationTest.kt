package io.github.tanzir71.harkara.mesh

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.BuildConfig
import io.github.tanzir71.harkara.core.lan.NodeSite
import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.settings.AppSettings
import io.github.tanzir71.harkara.settings.Module
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OffgridContainerIntegrationTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `production container defaults off and share policy exposes its installed APK until dismissed`() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        base.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val installedBytes = "installed Harkara APK fixture".encodeToByteArray()
        val installed = files.newFile("base.apk").apply { writeBytes(installedBytes) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(base.applicationInfo).apply {
                sourceDir = installed.absolutePath
            }
        }
        val container = AppContainer(context, inMemoryDatabase = true)
        try {
            assertFalse(container.settings.state.value.isOn(Module.MESH))
            assertFalse(container.settings.state.value.isOn(Module.NODE))
            assertFalse(container.mesh.running)
            assertFalse(container.mesh.status.value.running)
            assertFalse(container.node.node.value)
            assertFalse(container.node.sharing.value)
            assertFalse(container.lanRoom.running.value)
            assertTrue(container.relayPool.status.value.isEmpty())

            // Android NSD is Robolectric-shadowed; no internet relays or mesh radios are started.
            container.node.startSharing()
            val site = container.lanRoom.http
            assertNotNull(site)
            val apk = site!!.handle(NodeSite.APK_PATH)!!
            apk.open().use { assertArrayEquals(installedBytes, it.readBytes()) }
            val metadata = site.handle("/latest.json")!!
            val json = metadata.open().bufferedReader().use { it.readText() }
            assertTrue(json.contains(Hex.encode(Hashes.sha256(installedBytes))))
            assertTrue(json.contains(BuildConfig.VERSION_NAME))
            assertFalse(container.node.node.value)

            container.node.stopSharing()
            assertFalse(container.node.sharing.value)
            assertTrue(container.lanRoom.http == null)
            container.stopLan()
            assertFalse(container.lanRoom.running.value)
        } finally {
            container.mesh.stop()
            container.node.stopSharing()
            container.node.stopNode(keepRoom = false)
            container.lanRoom.stop()
            container.relayPool.stop()
            container.scope.coroutineContext[Job]!!.cancelAndJoin()
            container.database.close()
        }
    }
}

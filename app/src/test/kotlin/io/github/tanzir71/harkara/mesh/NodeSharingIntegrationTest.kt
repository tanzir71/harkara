package io.github.tanzir71.harkara.mesh

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.lan.NodeSite
import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.lan.FakeLanNetwork
import io.github.tanzir71.harkara.lan.LanRoom
import io.github.tanzir71.harkara.lan.NodeController
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RoomEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NodeSharingIntegrationTest {
    @get:Rule val files = TemporaryFolder()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val pool = RelayPool(scope)
    private val room = LanRoom(scope, FakeLanNetwork().discovery(), InetAddress.getByName("127.0.0.1"))
    private val client = OkHttpClient()
    private var node: NodeController? = null

    @After
    fun tearDown() = runBlocking {
        node?.stopSharing()
        node?.stopNode(keepRoom = false)
        room.stop()
        pool.stop()
        job.cancelAndJoin()
        db.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        Unit
    }

    private fun get(path: String) = client.newCall(Request.Builder().url("http://127.0.0.1:${room.port}$path").build()).execute()

    @Test
    fun `sharing serves the installed APK only while opted in and node extras follow node lifecycle`() {
        val apkBytes = ByteArray(4_097) { (it % 251).toByte() }
        val apk = files.newFile("installed.apk").apply { writeBytes(apkBytes) }
        val mapBytes = "offline Dhaka map".encodeToByteArray()
        val map = files.newFile("dhaka.pmtiles").apply { writeBytes(mapBytes) }
        val sha256 = Hex.encode(Hashes.sha256(apkBytes))
        val controller = NodeController(scope, room, db, RoomEventStore(db), pool, site = { isNode ->
            NodeSite(
                app = { NodeSite.AppFile(apk, "0.6.0", 600, sha256) },
                mapPacks = { listOf(map) }, handbook = { if (it == "index.json") "[]".encodeToByteArray() else null },
                node = isNode,
            )
        }).also { node = it }
        assertFalse(controller.node.value)
        assertFalse(controller.sharing.value)
        assertFalse(room.running.value)
        assertNull(room.http)

        controller.startSharing()
        assertTrue(controller.sharing.value)
        assertFalse(room.node)
        get("/").use { response ->
            assertEquals(200, response.code)
            val page = response.body!!.string()
            assertTrue(page.contains("Phone to phone"))
            assertTrue(page.contains(NodeSite.APK_PATH))
            assertTrue(page.contains(sha256))
            assertFalse(page.contains("/maps/"))
            assertFalse(page.contains("/handbook/"))
        }
        get(NodeSite.APK_PATH).use { response ->
            assertEquals(200, response.code)
            assertEquals(NodeSite.APK_TYPE, response.header("Content-Type"))
            assertTrue(response.header("Content-Disposition")!!.contains("harkara-0.6.0.apk"))
            assertArrayEquals(apkBytes, response.body!!.bytes())
        }
        get("/latest.json").use { response ->
            val json = response.body!!.string()
            assertTrue(json.contains("\"sha256\":\"$sha256\""))
            assertTrue(json.contains("\"bytes\":${apkBytes.size}"))
            assertTrue(json.contains("\"url\":\"${NodeSite.APK_PATH}\""))
        }
        get("/maps/dhaka.pmtiles").use { assertEquals(404, it.code) }
        get("/handbook/index.json").use { assertEquals(404, it.code) }

        controller.startNode()
        assertTrue(controller.node.value)
        assertTrue(room.node)
        get("/").use { assertTrue(it.body!!.string().contains("Neighbourhood node")) }
        get("/maps/dhaka.pmtiles").use { assertArrayEquals(mapBytes, it.body!!.bytes()) }
        get("/handbook/index.json").use { assertEquals("[]", it.body!!.string()) }
        get("/handbook/../installed.apk").use { assertEquals(404, it.code) }

        controller.stopNode(keepRoom = true)
        assertFalse(room.node)
        assertTrue(controller.sharing.value)
        get(NodeSite.APK_PATH).use { assertArrayEquals(apkBytes, it.body!!.bytes()) }
        get("/maps/dhaka.pmtiles").use { assertEquals(404, it.code) }
        controller.stopSharing()
        assertFalse(controller.sharing.value)
        assertNull(room.http)
        get(NodeSite.APK_PATH).use { response -> assertFalse(response.body!!.bytes().contentEquals(apkBytes)) }
    }

    @Test
    fun `stopping node in background stops the listener and advertisement`() {
        val controller = NodeController(scope, room, db, RoomEventStore(db), pool, site = { NodeSite(app = { null }, node = it) }).also { node = it }
        controller.startNode()
        assertTrue(room.running.value)
        controller.stopNode(keepRoom = false)
        assertFalse(controller.node.value)
        assertFalse(room.node)
        assertFalse(room.running.value)
        assertNull(room.http)
        assertEquals(0, pool.connectedCount)
    }
}

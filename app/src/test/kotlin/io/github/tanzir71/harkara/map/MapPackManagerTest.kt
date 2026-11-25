package io.github.tanzir71.harkara.map

import io.github.tanzir71.harkara.core.map.MapPacks
import io.github.tanzir71.harkara.core.nostr.Hex
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Map packs from a fake GitHub (MockWebServer): never the real network. */
class MapPackManagerTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private val pack = javaClass.getResourceAsStream("/pmtiles/dhaka-fixture.pmtiles")!!.use { it.readBytes() }
    private var served: ByteArray = pack
    private var manifestSha = sha(pack)
    private var requests = mutableListOf<String>()

    private fun sha(b: ByteArray) = Hex.encode(MessageDigest.getInstance("SHA-256").digest(b))

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("maps").toFile()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.path!!
                val base = server.url("/").toString().trimEnd('/')
                return when {
                    request.path!!.startsWith("/repos/") -> MockResponse().setBody(
                        """[{"tag_name":"v0.8.0","draft":false,"assets":[]},
                           {"tag_name":"maps-202611","draft":false,"assets":[
                              {"name":"maps.json","browser_download_url":"$base/dl/maps.json"},
                              {"name":"dhaka-z13.pmtiles","browser_download_url":"$base/dl/dhaka-z13.pmtiles"},
                              {"name":"dhaka-z15.pmtiles","browser_download_url":"$base/dl/dhaka-z15.pmtiles"}]}]""",
                    )
                    request.path == "/dl/maps.json" -> MockResponse().setBody(
                        """{"sourceDate":"20261124","bbox":[90.30,23.65,90.55,23.92],"attribution":"© OpenStreetMap contributors · Protomaps",
                           "packs":[{"name":"dhaka-z15.pmtiles","maxzoom":15,"bytes":${pack.size},"sha256":"$manifestSha"},
                                    {"name":"dhaka-z13.pmtiles","maxzoom":13,"bytes":${pack.size},"sha256":"$manifestSha"}]}""",
                    )
                    request.path!!.startsWith("/dl/dhaka-") -> MockResponse().setBody(Buffer().write(served))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun manager(free: Long = 10_000_000_000) =
        MapPackManager(dir, releasesUrl = server.url("/repos/tanzir71/harkara/releases").toString(), freeBytes = { free })

    @Test
    fun `downloads the chosen pack only when asked, verifies it and installs it`() = runBlocking<Unit> {
        val m = manager()
        assertNull(m.installed())
        assertTrue(requests.isEmpty())
        val state = m.check() as PackState.Available
        assertEquals("maps-202611", state.release.tag)
        val choice = m.recommended(state.manifest, lite = false)
        assertEquals(MapPacks.DETAILED, choice.name)
        assertEquals(MapPacks.SCHEMATIC, m.recommended(state.manifest, lite = true).name)

        val installed = m.download(state.release, state.manifest, choice)
        assertEquals(MapPacks.DETAILED, installed.name)
        assertEquals(14, installed.maxZoom)
        assertEquals(manifestSha, installed.sha256)
        assertEquals("20261124", installed.sourceDate)
        assertTrue(File(installed.path).readBytes().contentEquals(pack))
        assertEquals(installed, m.installed())
        assertEquals(PackState.Idle(installed), m.state.value)
        assertEquals(installed, manager().installed())
        assertFalse(File(dir, "dhaka-z15.pmtiles.part").exists())
    }

    @Test
    fun `a checksum mismatch is rejected and nothing is installed`() = runBlocking<Unit> {
        served = pack.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val m = manager()
        val state = m.check() as PackState.Available
        try {
            m.download(state.release, state.manifest, state.manifest.packs.first())
            fail("a corrupted pack must not install")
        } catch (e: MapPackException) {
            assertEquals(PackState.Reason.CHECKSUM, e.reason)
        }
        assertNull(m.installed())
        assertEquals(PackState.Reason.CHECKSUM, (m.state.value as PackState.Failed).reason)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".pmtiles") || it.name.endsWith(".part") })
    }

    @Test
    fun `a file with the right checksum but no map in it is refused`() = runBlocking<Unit> {
        served = "not a map at all".toByteArray()
        manifestSha = sha(served)
        val m = manager()
        val state = m.check() as PackState.Available
        val junk = state.manifest.packs.first().copy(bytes = served.size.toLong())
        val e = runCatching { m.download(state.release, state.manifest, junk) }.exceptionOrNull() as MapPackException
        assertEquals(PackState.Reason.NOT_A_MAP, e.reason)
        assertNull(m.installed())
    }

    @Test
    fun `not enough space, offline and no release fail cleanly`() = runBlocking<Unit> {
        val tight = manager(free = 1_000)
        val state = tight.check() as PackState.Available
        assertEquals(PackState.Reason.NO_SPACE, (runCatching { tight.download(state.release, state.manifest, state.manifest.packs.first()) }.exceptionOrNull() as MapPackException).reason)

        val missing = MapPackManager(dir, releasesUrl = server.url("/nothing").toString())
        assertEquals(PackState.Reason.NO_RELEASE, (missing.check() as PackState.Failed).reason)
        val offline = MapPackManager(dir, releasesUrl = "http://127.0.0.1:1/releases")
        assertEquals(PackState.Reason.OFFLINE, (offline.check() as PackState.Failed).reason)
    }

    @Test
    fun `a pack passed phone to phone imports after the same checks`() = runBlocking<Unit> {
        val m = manager()
        val file = File.createTempFile("from-friend", ".pmtiles").apply { writeBytes(pack); deleteOnExit() }
        val installed = m.import(file, "dhaka-z13.pmtiles", expectedSha256 = manifestSha)
        assertEquals("dhaka-z13.pmtiles", installed.name)
        assertTrue(File(installed.path).exists())

        val bad = File.createTempFile("bad", ".pmtiles").apply { writeText("hello"); deleteOnExit() }
        assertEquals(PackState.Reason.NOT_A_MAP, (runCatching { m.import(bad) }.exceptionOrNull() as MapPackException).reason)
        assertEquals(PackState.Reason.CHECKSUM, (runCatching { m.import(file, expectedSha256 = "00".repeat(32)) }.exceptionOrNull() as MapPackException).reason)
        // The earlier pack is untouched by failed imports.
        assertEquals(installed, m.installed())
    }
}

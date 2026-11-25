package io.github.tanzir71.harkara.map

import io.github.tanzir71.harkara.core.map.MapPacks
import io.github.tanzir71.harkara.core.nostr.Hex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Map packs from a fake GitHub (MockWebServer): never the real network. */
class MapPackManagerTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private val pack = javaClass.getResourceAsStream("/pmtiles/dhaka-fixture.pmtiles")!!.use { it.readBytes() }
    private var served: ByteArray = pack
    private var manifestSha = sha(pack)
    private var requests = mutableListOf<String>()
    @Volatile private var slowDownload = false

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
                    request.path!!.startsWith("/dl/dhaka-") -> MockResponse().setBody(Buffer().write(served)).apply {
                        if (slowDownload) throttleBody(1, 10, TimeUnit.MILLISECONDS)
                    }
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

    private fun manager(free: Long? = null): MapPackManager {
        val url = server.url("/repos/tanzir71/harkara/releases").toString()
        return if (free == null) MapPackManager(dir, releasesUrl = url)
        else MapPackManager(dir, releasesUrl = url, freeBytes = { free })
    }

    private fun source(name: String = "friend.input") = File(dir, name).apply { writeBytes(pack) }

    private fun assertPreserved(m: MapPackManager, installed: InstalledPack, info: ByteArray) {
        assertEquals(installed, m.installed())
        assertEquals(installed, manager().installed())
        assertArrayEquals(pack, File(installed.path).readBytes())
        assertArrayEquals(info, File(dir, "pack.json").readBytes())
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith(".map-pack-") })
        assertEquals(listOf(File(installed.path)), dir.listFiles().orEmpty().filter { it.name.endsWith(".pmtiles") })
    }

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

    @Test
    fun `fresh nested maps directory downloads with real default usable space`() = runBlocking<Unit> {
        val fresh = File(dir, "files/maps")
        assertFalse(fresh.exists())
        val m = MapPackManager(fresh, releasesUrl = server.url("/repos/tanzir71/harkara/releases").toString())
        val available = m.check() as PackState.Available
        assertFalse(fresh.exists())
        val installed = m.download(available.release, available.manifest, available.manifest.packs.first())
        assertTrue(fresh.isDirectory)
        assertArrayEquals(pack, File(installed.path).readBytes())
        assertEquals(installed, MapPackManager(fresh).installed())
    }

    @Test
    fun `fresh nested directory imports and recommends with actual default usable space`() = runBlocking<Unit> {
        val input = source()
        val fresh = File(dir, "files/maps")
        val m = MapPackManager(fresh, releasesUrl = server.url("/repos/tanzir71/harkara/releases").toString())
        val available = m.check() as PackState.Available
        assertEquals(MapPacks.DETAILED, m.recommended(available.manifest, false).name)
        assertTrue(fresh.isDirectory)
        val secondFresh = File(dir, "other/files/maps")
        val installed = MapPackManager(secondFresh).import(input, expectedSha256 = sha(pack))
        assertTrue(secondFresh.isDirectory)
        assertArrayEquals(pack, File(installed.path).readBytes())
    }

    @Test
    fun `replacement checksum invalid map and space failures preserve old archive and exact metadata`() = runBlocking<Unit> {
        val input = source()
        val m = manager()
        val installed = m.import(input)
        val info = File(dir, "pack.json").readBytes()
        repeat(3) {
            val error = runCatching { m.import(input, expectedSha256 = "0".repeat(64)) }.exceptionOrNull() as MapPackException
            assertEquals(PackState.Reason.CHECKSUM, error.reason)
            assertPreserved(m, installed, info)
        }
        val junk = File(dir, "junk.input").apply { writeText("not a map") }
        assertEquals(PackState.Reason.NOT_A_MAP, (runCatching { m.import(junk) }.exceptionOrNull() as MapPackException).reason)
        assertPreserved(m, installed, info)
        assertEquals(PackState.Reason.NO_SPACE, (runCatching { manager(free = 0).import(input) }.exceptionOrNull() as MapPackException).reason)
        assertPreserved(m, installed, info)

        val available = m.check() as PackState.Available
        served = pack.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertEquals(PackState.Reason.CHECKSUM, (runCatching { m.download(available.release, available.manifest, available.manifest.packs.first()) }.exceptionOrNull() as MapPackException).reason)
        assertPreserved(m, installed, info)
    }

    @Test
    fun `atomic archive promotion failure cannot destroy old installation`() = runBlocking<Unit> {
        val m = manager()
        val installed = m.import(source())
        val info = File(dir, "pack.json").readBytes()
        val available = m.check() as PackState.Available
        // Valid manifest alphabet, but exceeds the filesystem component limit: failure occurs at promotion,
        // after download, validation and durable staging, rather than relying on OS-dependent permissions.
        val name = "a".repeat(300) + ".pmtiles"
        val replacement = available.manifest.packs.first().copy(name = name)
        val release = available.release.copy(assets = mapOf(name to available.release.assets.getValue(MapPacks.DETAILED)))
        assertTrue(runCatching { m.download(release, available.manifest, replacement) }.exceptionOrNull() is MapPackException)
        assertPreserved(m, installed, info)
    }

    @Test
    fun `cancelled partial replacement download removes staging and preserves old installation`() = runBlocking<Unit> {
        val m = manager()
        val installed = m.import(source())
        val info = File(dir, "pack.json").readBytes()
        val available = m.check() as PackState.Available
        slowDownload = true
        val download = launch { m.download(available.release, available.manifest, available.manifest.packs.first()) }
        withTimeout(5000) { m.state.first { it is PackState.Downloading && it.done > 0 } }
        withTimeout(5000) { download.cancelAndJoin() }
        assertTrue(download.isCancelled)
        assertEquals(PackState.Idle(installed), m.state.value)
        assertPreserved(m, installed, info)
    }

    @Test
    fun `cancelled import cannot overwrite previous pack or leave staging`() = runBlocking<Unit> {
        val input = source()
        val m = manager()
        val installed = m.import(input)
        val info = File(dir, "pack.json").readBytes()
        lateinit var importJob: Job
        val cancelled = MapPackManager(dir, freeBytes = { importJob.cancel(); Long.MAX_VALUE })
        importJob = launch(start = CoroutineStart.LAZY) { cancelled.import(input) }
        importJob.start()
        withTimeout(5000) { importJob.join() }
        assertTrue(importJob.isCancelled)
        assertPreserved(m, installed, info)
    }

    @Test
    fun `same installed source and old fixed staging source import without truncation`() = runBlocking<Unit> {
        val m = manager()
        val first = m.import(source())
        val second = m.import(File(first.path), first.name, first.sha256)
        assertNotEquals(first.path, second.path)
        assertFalse(File(first.path).exists())
        assertArrayEquals(pack, File(second.path).readBytes())
        val oldPart = source("${MapPacks.DETAILED}.part")
        val third = m.import(oldPart, MapPacks.DETAILED, sha(pack))
        assertArrayEquals(pack, oldPart.readBytes())
        assertArrayEquals(pack, File(third.path).readBytes())
        assertFalse(File(second.path).exists())
    }

    @Test
    fun `successful replacement commits metadata then cleans previous maps and abandoned staging`() = runBlocking<Unit> {
        val m = manager()
        val input = source()
        val first = m.import(input)
        val abandoned = File(dir, ".map-pack-abandoned.part").apply { writeText("incomplete") }
        val second = m.import(input, MapPacks.SCHEMATIC, sha(pack))
        assertFalse(abandoned.exists())
        assertFalse(File(first.path).exists())
        assertEquals(MapPacks.SCHEMATIC, second.name)
        assertTrue(File(second.path).name.endsWith(".pmtiles"))
        assertEquals(second, MapPackManager(dir).installed())
        assertArrayEquals(pack, File(second.path).readBytes())
        assertEquals(listOf(File(second.path)), dir.listFiles().orEmpty().filter { it.name.endsWith(".pmtiles") })
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith(".map-pack-") })
    }
}

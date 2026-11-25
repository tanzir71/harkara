package io.github.tanzir71.harkara.core.lan

import io.github.tanzir71.harkara.core.relay.LocalRelay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI

/** Phone-to-phone install and the node's files over the room server's plain HTTP side. */
class NodeSiteTest {
    @TempDir
    lateinit var dir: File

    private val server = LanRelayServer(LocalRelay())

    @AfterEach
    fun tearDown() = server.stop()

    private fun get(path: String, accept: String? = null): Triple<Int, Map<String, String>, ByteArray> {
        val c = URI("http://127.0.0.1:${server.port}$path").toURL().openConnection() as HttpURLConnection
        accept?.let { c.setRequestProperty("Accept", it) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).use { it.readBytes() }
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
        return Triple(code, headers, body)
    }

    @Test
    fun `serves the download page, the apk and latest json`() {
        val apk = File(dir, "base.apk").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        server.http = NodeSite({ NodeSite.AppFile(apk, "0.5.0", 500, "ab".repeat(32)) })
        server.start(InetAddress.getLoopbackAddress())

        val (code, headers, page) = get("/")
        assertEquals(200, code)
        assertTrue(headers.getValue("content-type").startsWith("text/html"))
        val html = page.decodeToString()
        assertTrue("/harkara.apk" in html && "0.5.0" in html && "ab".repeat(32) in html)
        assertTrue("কীভাবে ইনস্টল করবেন" in html && "How to install" in html)

        val (apkCode, apkHeaders, bytes) = get("/harkara.apk")
        assertEquals(200, apkCode)
        assertEquals(NodeSite.APK_TYPE, apkHeaders["content-type"])
        assertTrue(apkHeaders.getValue("content-disposition").contains("harkara-0.5.0.apk"))
        assertArrayEquals(apk.readBytes(), bytes)

        val latest = get("/latest.json").third.decodeToString()
        assertTrue("\"versionCode\":500" in latest && "\"bytes\":300000" in latest)

        // A share server is not a node: no maps or handbook, and nothing outside the fixed names.
        assertEquals(404, get("/maps/dhaka-z15.pmtiles").first)
        assertEquals(404, get("/../base.apk").first)
        // NIP-11 still answers for relay clients.
        assertTrue(get("/", accept = "application/nostr+json").third.decodeToString().contains("supported_nips"))
    }

    @Test
    fun `a node also serves map packs and the handbook`() {
        val apk = File(dir, "base.apk").apply { writeBytes(ByteArray(10)) }
        val pack = File(dir, "dhaka-z15.pmtiles").apply { writeBytes(ByteArray(4_096) { 7 }) }
        val handbook = mapOf("index.json" to """{"sections":[]}""", "en/cpr.md" to "# CPR")
        server.http = NodeSite(
            app = { NodeSite.AppFile(apk, "0.5.0", 500, "00".repeat(32)) },
            mapPacks = { listOf(pack) },
            handbook = { handbook[it]?.encodeToByteArray() },
            node = true,
        )
        server.start(InetAddress.getLoopbackAddress())

        assertTrue(get("/").third.decodeToString().contains("/maps/dhaka-z15.pmtiles"))
        assertArrayEquals(pack.readBytes(), get("/maps/dhaka-z15.pmtiles").third)
        assertEquals("# CPR", get("/handbook/en/cpr.md").third.decodeToString())
        assertEquals(404, get("/handbook/../secret").first)
        assertEquals(404, get("/maps/other.pmtiles").first)
    }
}

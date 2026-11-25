package io.github.tanzir71.harkara.core.tools

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalSendTest {
    @TempDir lateinit var directory: File
    private val servers = mutableListOf<LocalSendServer>()
    private val device = LocalSendDevice("Test receiver", fingerprint = "display-only")
    private val json = LocalSendServer.wireJson
    private val bytes = "hello from the hall".toByteArray()
    private val metadata = LocalSendFile("one", "hello.txt", bytes.size.toLong())

    @AfterEach fun close() = servers.forEach { it.close() }

    private fun server(
        consent: (LocalSendOffer) -> CompletableFuture<Set<String>> = { CompletableFuture.completedFuture(it.files.keys) },
        received: (LocalSendReceived) -> Unit = {},
        limits: LocalSendLimits = LocalSendLimits(),
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        ptt: PttReceiver? = null,
    ): LocalSendServer = LocalSendServer(device, directory, consent, received, limits, ptt, clock).also {
        servers += it
        assertFalse(it.isRunning)
        assertEquals(-1, it.port)
        it.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private data class Response(val code: Int, val body: ByteArray)
    private fun request(server: LocalSendServer, method: String, path: String, body: ByteArray = byteArrayOf(), length: Int = body.size): Response =
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 4000
            socket.getOutputStream().apply {
                write(("$method $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: $length\r\n\r\n").toByteArray())
                write(body)
                flush()
            }
            socket.shutdownOutput()
            val response = socket.getInputStream().readBytes()
            val text = response.toString(Charsets.ISO_8859_1)
            val end = text.indexOf("\r\n\r\n")
            Response(text.split(' ')[1].toInt(), response.copyOfRange(end + 4, response.size))
        }

    private fun prepare(server: LocalSendServer, files: Map<String, LocalSendFile> = mapOf("one" to metadata)): Response =
        request(server, "POST", "${LocalSendServer.API}/prepare-upload", json.encodeToString(LocalSendPrepare(device, files)).toByteArray())

    private fun path(prepared: LocalSendPrepared, token: String = prepared.files.getValue("one")) =
        "${LocalSendServer.API}/upload?sessionId=${prepared.sessionId}&fileId=one&token=$token"

    @Test fun `info and registration expose actual dedicated port and explicit lifecycle`() {
        val server = server()
        val info = json.decodeFromString<LocalSendDevice>(request(server, "GET", "${LocalSendServer.API}/info").body.decodeToString())
        assertEquals(server.port, info.port)
        assertEquals("http", info.protocol)
        assertEquals("2.1", info.version)
        assertEquals(200, request(server, "POST", "${LocalSendServer.API}/register", "{}".toByteArray()).code)
        server.close()
        assertFalse(server.isRunning)
        assertEquals(-1, server.port)
    }

    @Test fun `real loopback prepare and streamed upload require consent and preserve metadata not path`() {
        val received = CopyOnWriteArrayList<LocalSendReceived>()
        val server = server(received = { received += it })
        val prepared = json.decodeFromString<LocalSendPrepared>(prepare(server).body.decodeToString())
        assertEquals(403, request(server, "POST", path(prepared, "wrong"), bytes).code)
        assertEquals(200, request(server, "POST", path(prepared), bytes).code)
        assertEquals(403, request(server, "POST", path(prepared), bytes).code)
        assertEquals(1, received.size)
        assertArrayEquals(bytes, received.single().file.readBytes())
        assertEquals(metadata, received.single().metadata)
        assertNotEquals(metadata.fileName, received.single().file.name)
        assertEquals(directory.canonicalFile, received.single().file.parentFile.canonicalFile)
    }

    @Test fun `default consent rejects even a repeated claimed fingerprint`() {
        val server = LocalSendServer(device, directory).also { servers += it; it.start(InetAddress.getLoopbackAddress(), 0) }
        assertEquals(403, prepare(server).code)
        assertEquals(403, prepare(server).code)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `only explicitly selected file IDs receive upload tokens`() {
        val server = server(consent = { CompletableFuture.completedFuture(setOf("one")) })
        val prepared = json.decodeFromString<LocalSendPrepared>(prepare(server, mapOf("one" to metadata,
            "two" to metadata.copy(id = "two", fileName = "two.txt"))).body.decodeToString())
        assertEquals(setOf("one"), prepared.files.keys)
    }

    @Test fun `unsafe names IDs and oversized offers fail before asking consent`() {
        var asked = 0
        val server = server(consent = { asked++; CompletableFuture.completedFuture(it.files.keys) })
        listOf("../escape", "a/b", "a\\b", ".", "..", "C:evil", "bad\u0000.txt").forEach { name ->
            assertEquals(400, prepare(server, mapOf("one" to metadata.copy(fileName = name))).code, name)
        }
        assertEquals(400, prepare(server, mapOf("one" to metadata.copy(size = Long.MAX_VALUE))).code)
        assertEquals(400, prepare(server, mapOf("one" to metadata.copy(id = "different"))).code)
        assertEquals(0, asked)
        assertEquals(413, request(server, "POST", "${LocalSendServer.API}/prepare-upload", ByteArray(65537)).code)
    }

    @Test fun `length mismatch hash mismatch and truncated stream cannot publish a file`() {
        val received = mutableListOf<LocalSendReceived>()
        val server = server(received = { received += it })
        val prepared = json.decodeFromString<LocalSendPrepared>(prepare(server).body.decodeToString())
        assertEquals(400, request(server, "POST", path(prepared), bytes.copyOf(2)).code)
        assertEquals(400, request(server, "POST", path(prepared), bytes.copyOf(2), bytes.size).code)
        assertEquals(403, request(server, "POST", path(prepared), bytes).code)
        val hashOffer = json.decodeFromString<LocalSendPrepared>(prepare(server, mapOf("one" to metadata.copy(sha256 = "0".repeat(64)))).body.decodeToString())
        assertEquals(400, request(server, "POST", path(hashOffer), bytes).code)
        assertTrue(received.isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `valid sha256 and empty file are accepted without changing names`() {
        val received = mutableListOf<LocalSendReceived>()
        val server = server(received = { received += it })
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val prepared = json.decodeFromString<LocalSendPrepared>(prepare(server, mapOf("one" to metadata.copy(sha256 = hash))).body.decodeToString())
        assertEquals(200, request(server, "POST", path(prepared), bytes).code)
        val empty = json.decodeFromString<LocalSendPrepared>(prepare(server, mapOf("one" to metadata.copy(size = 0))).body.decodeToString())
        assertEquals(200, request(server, "POST", path(empty)).code)
        assertEquals(2, received.size)
    }

    @Test fun `expired and canceled sessions cannot upload`() {
        var now = 100L
        val server = server(clock = { now })
        val expired = json.decodeFromString<LocalSendPrepared>(prepare(server).body.decodeToString())
        now += 120_001
        assertEquals(403, request(server, "POST", path(expired), bytes).code)
        val canceled = json.decodeFromString<LocalSendPrepared>(prepare(server).body.decodeToString())
        assertEquals(200, request(server, "POST", "${LocalSendServer.API}/cancel?sessionId=${canceled.sessionId}").code)
        assertEquals(403, request(server, "POST", path(canceled), bytes).code)
    }

    @Test fun `consent deadline denies request and does not leak session capacity`() {
        val futures = mutableListOf<CompletableFuture<Set<String>>>()
        val server = server(consent = { CompletableFuture<Set<String>>().also { futures += it } },
            limits = LocalSendLimits(maxSessions = 1, consentTimeoutMillis = 20))
        assertEquals(403, prepare(server).code)
        assertEquals(403, prepare(server).code)
        assertTrue(futures.all { it.isCancelled })
    }

    @Test fun `stop cancels outstanding user consent and no late approval survives restart`() {
        val asked = CountDownLatch(1)
        val choice = CompletableFuture<Set<String>>()
        val server = server(consent = { asked.countDown(); choice })
        val request = CompletableFuture.runAsync { runCatching { prepare(server) } }
        assertTrue(asked.await(2, TimeUnit.SECONDS))
        server.close()
        request.get(2, TimeUnit.SECONDS)
        assertTrue(choice.isCancelled)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}

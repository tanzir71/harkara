package io.github.tanzir71.harkara.core.tools

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** A bounded, explicitly started HTTP/1.1 listener dedicated to LAN tools, not the chat relay. */
internal class LanToolsHttp(private val handler: (LanRequest) -> LanResponse) : AutoCloseable {
    @Volatile private var listener: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    val port: Int get() = listener?.localPort ?: -1

    @Synchronized
    fun start(address: InetAddress?, port: Int): Int {
        if (listener != null) return this.port
        val server = ServerSocket()
        try {
            server.bind(InetSocketAddress(address, port), 4)
        } catch (e: Exception) {
            server.close()
            throw e
        }
        listener = server
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                synchronized(this) {
                    if (listener !== server || clients.size >= 4) socket.close()
                    else {
                        clients.add(socket)
                        Thread({ serve(socket) }, "harkara-tools-http").apply { isDaemon = true }.start()
                    }
                }
            }
        }, "harkara-tools-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    @Synchronized
    override fun close() {
        listener?.close()
        listener = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 30_000
                val input = BufferedInputStream(socket.getInputStream())
                val response = try {
                    val address = socket.inetAddress
                    val ula = address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc
                    if (!(address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || ula)) throw LanHttpError(403)
                    handler(parse(socket, input))
                }
                catch (e: LanHttpError) { LanResponse(e.status) }
                catch (_: IllegalArgumentException) { LanResponse(400) }
                catch (_: Exception) { LanResponse(500) }
                val reason = when (response.status) {
                    200 -> "OK"
                    204 -> "No Content"
                    400 -> "Bad Request"
                    403 -> "Forbidden"
                    404 -> "Not Found"
                    409 -> "Conflict"
                    413 -> "Payload Too Large"
                    429 -> "Too Many Requests"
                    503 -> "Unavailable"
                    else -> "Error"
                }
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 ${response.status} $reason\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${response.body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                    write(response.body)
                    flush()
                }
            } catch (_: IOException) {
                // Timeout, stop(), or peer disconnection. No request survives closing the listener.
            } finally { clients.remove(socket) }
        }
    }

    private fun parse(socket: Socket, input: InputStream): LanRequest {
        val header = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val byte = input.read()
            if (byte < 0 || header.size() >= 8192) throw LanHttpError(400)
            header.write(byte)
            tail = (tail shl 8) or byte
            if (tail == 0x0d0a0d0a) break
        }
        val lines = header.toString("US-ASCII").split("\r\n")
        val first = lines.first().split(' ')
        if (first.size != 3 || first[2] != "HTTP/1.1" || first[1].length > 2048) throw LanHttpError(400)
        val headers = mutableMapOf<String, String>()
        lines.drop(1).filter { it.isNotEmpty() }.forEach {
            val colon = it.indexOf(':')
            if (colon <= 0) throw LanHttpError(400)
            val name = it.substring(0, colon).lowercase()
            if (headers.put(name, it.substring(colon + 1).trim()) != null) throw LanHttpError(400)
        }
        // A single known length prevents ambiguous framing and unbounded/chunked request bodies.
        if (headers.containsKey("transfer-encoding")) throw LanHttpError(400)
        val length = headers["content-length"]?.toLongOrNull() ?: if (first[0] == "GET") 0 else throw LanHttpError(400)
        if (length < 0) throw LanHttpError(400)
        val target = first[1].split('?', limit = 2)
        if (!target[0].startsWith('/')) throw LanHttpError(400)
        val query = mutableMapOf<String, String>()
        target.getOrNull(1)?.split('&')?.filter { it.isNotEmpty() }?.forEach {
            val pair = it.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], "UTF-8")
            if (query.put(key, URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")) != null) throw LanHttpError(400)
        }
        return LanRequest(first[0], target[0], query, socket.inetAddress.hostAddress!!, length, input)
    }
}

internal data class LanRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val remoteAddress: String,
    val length: Long,
    val input: InputStream,
) {
    fun body(limit: Int): ByteArray {
        if (length > limit) throw LanHttpError(413)
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) throw LanHttpError(400)
            offset += n
        }
        return bytes
    }
}

internal data class LanResponse(val status: Int = 200, val body: ByteArray = byteArrayOf())
internal class LanHttpError(val status: Int) : RuntimeException()

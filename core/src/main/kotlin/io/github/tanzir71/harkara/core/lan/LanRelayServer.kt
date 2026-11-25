package io.github.tanzir71.harkara.core.lan

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.relay.ClientMessage
import io.github.tanzir71.harkara.core.relay.LocalRelay
import io.github.tanzir71.harkara.core.relay.RelayMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The "everyone on this Wi-Fi" room server: a small HTTP + WebSocket (RFC 6455) server around a [LocalRelay],
 * written on plain sockets so it runs the same on Android and in JVM tests. Peers connect with any NIP-01
 * client (the app's OkHttp pool); `GET /` with `Accept: application/nostr+json` returns NIP-11, other plain
 * requests get a one-line text page. Events published by peers are handed to [onEvent] (this phone's own
 * inbox); events of this phone go in through [inject]. Text frames only, at most [maxFrameBytes] each,
 * [maxClients] at once; pings are answered so OkHttp's keep-alive holds.
 */
class LanRelayServer(
    private val relay: LocalRelay,
    private val onEvent: (Event) -> Unit = {},
    private val maxClients: Int = 32,
    private val maxFrameBytes: Int = 128 * 1024,
) {
    private var server: ServerSocket? = null
    private val clients = ConcurrentHashMap<String, Client>()
    private val ids = AtomicInteger()
    private val mutableClients = MutableStateFlow(0)

    /** Peers connected to this phone's room right now. */
    val clientCount: StateFlow<Int> = mutableClients.asStateFlow()

    val port: Int get() = server?.localPort ?: -1

    val isRunning: Boolean get() = server?.isClosed == false

    /** Starts listening on [address] (null: every interface) and [port] (0: any free port). Returns the port. */
    @Synchronized
    fun start(address: InetAddress? = null, port: Int = 0): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, port), 16)
        server = socket
        Thread({ acceptLoop(socket) }, "harkara-lan-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        clients.values.forEach { it.close() }
        clients.clear()
        mutableClients.value = 0
    }

    /** Adds this phone's own event to the room: stored (unless ephemeral) and pushed to subscribed peers. */
    fun inject(event: Event) = route(relay.inject(event))

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val s = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            if (clients.size >= maxClients) {
                runCatching { s.close() }
                continue
            }
            val client = Client("lan-${ids.incrementAndGet()}", s)
            Thread({ client.run() }, "harkara-lan-${client.id}").apply { isDaemon = true }.start()
        }
    }

    private fun route(out: List<LocalRelay.Outgoing>) {
        out.forEach { o -> clients[o.clientId]?.sendText(o.text) }
    }

    private inner class Client(val id: String, private val socket: Socket) {
        private lateinit var output: OutputStream
        @Volatile private var open = false

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                output = socket.getOutputStream()
                val request = readHeaders(input) ?: return close()
                val key = request.headers["sec-websocket-key"]
                if (request.headers["upgrade"]?.equals("websocket", ignoreCase = true) != true || key == null) {
                    serveHttp(request)
                    return close()
                }
                handshake(key)
                socket.soTimeout = 0
                open = true
                clients[id] = this
                relay.connect(id)
                mutableClients.value = clients.size
                readFrames(input)
            } catch (_: IOException) {
                // Peer went away or sent garbage: just drop it.
            } finally {
                close()
            }
        }

        private fun serveHttp(request: Request) {
            val nostr = request.headers["accept"]?.contains("application/nostr+json") == true
            val body = if (nostr) INFO_JSON else "Harkara LAN room\n"
            val type = if (nostr) "application/nostr+json" else "text/plain; charset=utf-8"
            val bytes = body.encodeToByteArray()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
            output.write(head.encodeToByteArray())
            if (request.method != "HEAD") output.write(bytes)
            output.flush()
        }

        private fun handshake(key: String) {
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key.trim() + WS_GUID).encodeToByteArray()),
            )
            val head = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
            synchronized(this) {
                output.write(head.encodeToByteArray())
                output.flush()
            }
        }

        private fun readFrames(input: InputStream) {
            val message = ByteArrayOutputStream()
            var messageOpcode = -1
            while (open) {
                val b0 = input.read()
                if (b0 < 0) return
                val b1 = readByte(input)
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                val masked = b1 and 0x80 != 0
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = ((readByte(input) shl 8) or readByte(input)).toLong()
                else if (length == 127L) {
                    length = 0
                    repeat(8) { length = (length shl 8) or readByte(input).toLong() }
                }
                if (!masked) return sendClose(1002)
                if (length > maxFrameBytes || length < 0) return sendClose(1009)
                val mask = ByteArray(4).also { readFully(input, it) }
                val payload = ByteArray(length.toInt()).also { readFully(input, it) }
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    OP_CLOSE -> return sendClose(1000)
                    OP_PING -> sendFrame(OP_PONG, payload)
                    OP_PONG -> Unit
                    OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                        if (opcode != OP_CONTINUATION) {
                            message.reset()
                            messageOpcode = opcode
                        }
                        if (message.size() + payload.size > maxFrameBytes) return sendClose(1009)
                        message.write(payload)
                        if (fin) {
                            if (messageOpcode == OP_TEXT) onText(message.toString(Charsets.UTF_8.name()))
                            message.reset()
                            messageOpcode = -1
                        }
                    }
                    else -> return sendClose(1002)
                }
            }
        }

        private fun onText(text: String) {
            val published = (ClientMessage.parse(text) as? ClientMessage.Publish)?.event
            val out = relay.handle(id, text)
            route(out)
            if (published != null) {
                val ok = out.any { it.clientId == id && (RelayMessage.parse(it.text) as? RelayMessage.Ok)?.let { a -> a.eventId == published.id && a.accepted } == true }
                if (ok) runCatching { onEvent(published) }
            }
        }

        fun sendText(text: String) {
            if (open) runCatching { sendFrame(OP_TEXT, text.encodeToByteArray()) }.onFailure { close() }
        }

        private fun sendClose(code: Int) {
            runCatching { sendFrame(OP_CLOSE, byteArrayOf((code shr 8).toByte(), code.toByte())) }
            open = false
        }

        @Synchronized
        private fun sendFrame(opcode: Int, payload: ByteArray) {
            val header = ByteArrayOutputStream()
            header.write(0x80 or opcode)
            when {
                payload.size < 126 -> header.write(payload.size)
                payload.size <= 0xFFFF -> {
                    header.write(126)
                    header.write(payload.size shr 8)
                    header.write(payload.size and 0xFF)
                }
                else -> {
                    header.write(127)
                    for (shift in 56 downTo 0 step 8) header.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            output.write(header.toByteArray())
            output.write(payload)
            output.flush()
        }

        fun close() {
            open = false
            if (clients.remove(id) != null) {
                relay.disconnect(id)
                mutableClients.value = clients.size
            }
            runCatching { socket.close() }
        }
    }

    private class Request(val method: String, val headers: Map<String, String>)

    private fun readHeaders(input: InputStream): Request? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || buffer.size() > MAX_HEADER_BYTES) return null
            buffer.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = buffer.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 3) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        return Request(requestLine[0], headers)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

        /** NIP-11 for the room: limits peers' pools respect. */
        const val INFO_JSON: String =
            """{"name":"Harkara LAN room","description":"Everyone on this Wi-Fi","software":"harkara","supported_nips":[1,11,40,59],"limitation":{"max_message_length":131072,"max_subscriptions":20,"max_filters":10}}"""
    }
}

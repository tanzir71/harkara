package io.github.tanzir71.harkara.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Serializable
data class LocalSendDevice(
    val alias: String,
    val version: String = "2.1",
    val deviceModel: String = "Harkara",
    val deviceType: String = "mobile",
    val fingerprint: String,
    val port: Int = 53317,
    val protocol: String = "http",
    val download: Boolean = false,
)

@Serializable
data class LocalSendFile(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String = "application/octet-stream",
    val sha256: String? = null,
)

@Serializable
data class LocalSendPrepare(val info: LocalSendDevice, val files: Map<String, LocalSendFile>)

@Serializable
data class LocalSendPrepared(val sessionId: String, val files: Map<String, String>)

/** The device's fingerprint and alias are UNAUTHENTICATED claims, not an identity/trust decision. */
data class LocalSendOffer(val remoteAddress: String, val sender: LocalSendDevice, val files: Map<String, LocalSendFile>)
data class LocalSendReceived(val offer: LocalSendOffer, val metadata: LocalSendFile, val file: File)

data class LocalSendLimits(
    val maxFileBytes: Long = 25L * 1024 * 1024,
    val maxOfferBytes: Long = 100L * 1024 * 1024,
    val maxFiles: Int = 32,
    val maxSessions: Int = 4,
    val consentTimeoutMillis: Long = 25_000,
    val sessionMillis: Long = 120_000,
) {
    init {
        require(maxFileBytes in 1..(100L * 1024 * 1024))
        require(maxOfferBytes in maxFileBytes..(100L * 1024 * 1024))
        require(maxFiles in 1..64 && maxSessions in 1..4)
        require(consentTimeoutMillis in 1..25_000 && sessionMillis in 1..600_000)
    }

    fun validate(files: Map<String, LocalSendFile>) {
        require(files.isNotEmpty() && files.size <= maxFiles)
        var total = 0L
        files.forEach { (id, file) ->
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && file.id == id)
            require(file.fileName.isNotBlank() && file.fileName.toByteArray().size <= 240)
            require(file.fileName !in setOf(".", "..") && file.fileName.none { it < ' ' || it == '\u007f' || it in "/\\:" })
            require(file.size in 0..maxFileBytes)
            require(file.fileType.length in 1..128 && file.fileType.none { it < ' ' || it == '\u007f' })
            require(file.sha256 == null || file.sha256.matches(Regex("[a-fA-F0-9]{64}")))
            total += file.size
        }
        require(total <= maxOfferBytes)
    }
}

/**
 * LocalSend v2 info/register/prepare-upload/upload/cancel on a dedicated HTTP port. No discovery or
 * microphone is started by construction. Plain HTTP is for a user-selected trusted LAN only: it is
 * neither confidential nor authenticated. Every offer needs explicit consent; the default denies.
 *
 * Consent runs off the UI thread and waits at most 25 s for the returned future. Complete it with
 * selected file IDs after showing sender address, names and sizes. Never approve by fingerprint alone.
 * Received files use random private storage names; the advertised name is metadata, never a path.
 * The consumer owns completed files (including deletion/storage budget). Failed uploads are deleted.
 */
class LocalSendServer(
    private val device: LocalSendDevice,
    private val receiveDirectory: File,
    private val consent: (LocalSendOffer) -> CompletableFuture<Set<String>> = { CompletableFuture.completedFuture(emptySet()) },
    private val onReceived: (LocalSendReceived) -> Unit = {},
    private val limits: LocalSendLimits = LocalSendLimits(),
    private val ptt: PttReceiver? = null,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private data class Session(
        val offer: LocalSendOffer,
        val tokens: MutableMap<String, String>,
        val expires: Long,
        val active: MutableSet<File> = mutableSetOf(),
        var cancelled: Boolean = false,
    )
    private val lock = Any()
    private val sessions = mutableMapOf<String, Session>()
    private val pending = mutableSetOf<CompletableFuture<Set<String>>>()
    private var deciding = 0
    @Volatile private var started = false
    private var generation = 0L
    private val http = LanToolsHttp(::handle)
    val port: Int get() = http.port
    val isRunning: Boolean get() = started

    @Synchronized
    fun start(address: InetAddress? = null, port: Int = 53317): Int {
        if (started) return this.port
        require(device.alias.isNotBlank() && device.alias.length <= 128 && device.fingerprint.length in 1..128)
        require(receiveDirectory.isDirectory || receiveDirectory.mkdirs())
        val bound = http.start(address, port)
        started = true
        return bound
    }

    @Synchronized
    override fun close() {
        started = false
        synchronized(lock) {
            generation++
            pending.forEach { it.cancel(true) }
            pending.clear()
            sessions.values.forEach { cancel(it) }
            sessions.clear()
        }
        ptt?.disable()
        http.close()
    }

    private fun handle(request: LanRequest): LanResponse {
        if (!started) throw LanHttpError(503)
        if (request.path == PttReceiver.PATH && request.method == "POST") {
            return ptt?.handle(request) ?: LanResponse(403)
        }
        return when (request.method to request.path) {
            "GET" to "$API/info", "POST" to "$API/register" -> json(device.copy(port = port))
            "POST" to "$API/prepare-upload" -> prepare(request)
            "POST" to "$API/upload" -> upload(request)
            "POST" to "$API/cancel" -> synchronized(lock) {
                val session = sessions[request.query["sessionId"]] ?: throw LanHttpError(404)
                if (session.offer.remoteAddress != request.remoteAddress) throw LanHttpError(403)
                cancel(session)
                sessions.remove(request.query["sessionId"])
                LanResponse()
            }
            else -> LanResponse(404)
        }
    }

    private fun prepare(request: LanRequest): LanResponse {
        val data = wireJson.decodeFromString<LocalSendPrepare>(request.body(64 * 1024).decodeToString())
        limits.validate(data.files)
        require(data.info.version.startsWith("2.") && data.info.alias.length in 1..128 && data.info.fingerprint.length in 1..128)
        val offer = LocalSendOffer(request.remoteAddress, data.info, data.files.toMap())
        val epoch = synchronized(lock) {
            prune()
            if (!started || sessions.size + deciding >= limits.maxSessions) throw LanHttpError(429)
            deciding++
            generation
        }
        var future: CompletableFuture<Set<String>>? = null
        try {
            future = consent(offer)
            synchronized(lock) {
                if (!started || generation != epoch) throw LanHttpError(503)
                pending.add(future)
            }
            val accepted = try { future.get(limits.consentTimeoutMillis, TimeUnit.MILLISECONDS) }
            catch (_: Exception) { throw LanHttpError(403) }
            if (accepted.isEmpty() || !data.files.keys.containsAll(accepted)) throw LanHttpError(403)
            return synchronized(lock) {
                if (!started || generation != epoch) throw LanHttpError(503)
                val id = UUID.randomUUID().toString()
                val tokens = accepted.associateWith { UUID.randomUUID().toString() }.toMutableMap()
                sessions[id] = Session(offer, tokens, nowMillis() + limits.sessionMillis)
                json(LocalSendPrepared(id, tokens.toMap()))
            }
        } finally {
            synchronized(lock) {
                deciding--
                future?.let { pending.remove(it); if (!it.isDone) it.cancel(true) }
            }
        }
    }

    private fun upload(request: LanRequest): LanResponse {
        val id = request.query["fileId"] ?: throw LanHttpError(400)
        val (session, metadata, target) = synchronized(lock) {
            prune()
            val session = sessions[request.query["sessionId"]] ?: throw LanHttpError(403)
            if (session.cancelled || session.offer.remoteAddress != request.remoteAddress ||
                session.tokens[id] == null || session.tokens[id] != request.query["token"]) throw LanHttpError(403)
            val metadata = session.offer.files.getValue(id)
            if (request.length != metadata.size) throw LanHttpError(400)
            session.tokens.remove(id) // Single-use, even if this attempt is truncated or corrupt.
            val target = File.createTempFile("drop-", ".part", receiveDirectory)
            session.active.add(target)
            Triple(session, metadata, target)
        }
        var complete = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            target.outputStream().use { output ->
                var remaining = metadata.size
                val buffer = ByteArray(16 * 1024)
                while (remaining > 0) {
                    synchronized(lock) {
                        if (!started || session.cancelled || nowMillis() >= session.expires) throw LanHttpError(403)
                    }
                    val count = request.input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw LanHttpError(400)
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (metadata.sha256 != null && !hash.equals(metadata.sha256, true)) throw LanHttpError(400)
            synchronized(lock) {
                if (!started || session.cancelled || nowMillis() >= session.expires) throw LanHttpError(403)
                // Commit ownership before invoking application code; stop must not delete delivered files.
                session.active.remove(target)
            }
            onReceived(LocalSendReceived(session.offer, metadata, target))
            complete = true
            return LanResponse()
        } finally {
            synchronized(lock) {
                session.active.remove(target)
                if (session.tokens.isEmpty() && session.active.isEmpty()) sessions.values.remove(session)
            }
            if (!complete) target.delete()
        }
    }

    private fun prune() {
        val expired = sessions.filterValues { nowMillis() >= it.expires }.keys
        expired.forEach { sessions.remove(it)?.let(::cancel) }
    }

    private fun cancel(session: Session) {
        session.cancelled = true
        session.active.forEach { it.delete() }
    }

    private inline fun <reified T> json(value: T) = LanResponse(body = wireJson.encodeToString(value).toByteArray())

    companion object {
        const val API = "/api/localsend/v2"
        val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

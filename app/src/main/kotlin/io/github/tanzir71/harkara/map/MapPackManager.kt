package io.github.tanzir71.harkara.map

import io.github.tanzir71.harkara.core.map.MapPack
import io.github.tanzir71.harkara.core.map.MapPacks
import io.github.tanzir71.harkara.core.map.MapsManifest
import io.github.tanzir71.harkara.core.map.MapsRelease
import io.github.tanzir71.harkara.core.map.PmTileType
import io.github.tanzir71.harkara.core.map.PmTilesReader
import io.github.tanzir71.harkara.core.nostr.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.CoroutineContext

/** The pack on this phone. */
@Serializable
data class InstalledPack(val name: String, val maxZoom: Int, val bytes: Long, val sha256: String, val sourceDate: String = "", val path: String)

/** What the Map packs screen shows. */
sealed interface PackState {
    data class Idle(val installed: InstalledPack?) : PackState
    data object Checking : PackState
    data class Available(val installed: InstalledPack?, val release: MapsRelease, val manifest: MapsManifest) : PackState
    data class Downloading(val pack: MapPack, val done: Long, val total: Long) : PackState
    data class Failed(val reason: Reason, val installed: InstalledPack?) : PackState

    enum class Reason { OFFLINE, NO_RELEASE, CHECKSUM, NOT_A_MAP, NO_SPACE }
}

class MapPackException(val reason: PackState.Reason, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Offline map packs: finds the newest `maps-YYYYMM` release, downloads the chosen `.pmtiles` only when
 * the user asks, checks its SHA-256 against `maps.json` and that it really is a Dhaka vector pack, then
 * installs it atomically under `files/maps/`. [import] takes a pack another phone passed on (LAN/node,
 * file share), checked the same way.
 */
class MapPackManager(
    private val dir: File,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    private val releasesUrl: String = RELEASES_URL,
    private val freeBytes: () -> Long = { dir.usableSpace },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val directoryLock = locks.computeIfAbsent(dir.canonicalPath) { DirectoryLock() }
    private val mutableState = MutableStateFlow<PackState>(PackState.Idle(readInstalled()))
    val state: StateFlow<PackState> = mutableState.asStateFlow()

    fun installed(): InstalledPack? = readInstalled()

    /** Asks GitHub for the newest maps release and its manifest. */
    suspend fun check(): PackState {
        mutableState.value = PackState.Checking
        val result = try {
            val (release, manifest) = fetchManifest()
            PackState.Available(installed(), release, manifest)
        } catch (e: MapPackException) {
            PackState.Failed(e.reason, installed())
        }
        mutableState.value = result
        return result
    }

    suspend fun fetchManifest(): Pair<MapsRelease, MapsManifest> = withContext(Dispatchers.IO) {
        val releases = get(releasesUrl).use { it.readBytes().decodeToString() }
        val release = MapPacks.newestRelease(releases) ?: throw MapPackException(PackState.Reason.NO_RELEASE, "no maps release")
        val manifestText = get(release.manifestUrl!!).use { it.readBytes().decodeToString() }
        val manifest = runCatching { MapPacks.parseManifest(manifestText) }.getOrElse { throw MapPackException(PackState.Reason.NO_RELEASE, "bad manifest", it) }
        release to manifest
    }

    /** The pack this phone should get by default (z13 in Lite mode or when space is short). */
    fun recommended(manifest: MapsManifest, lite: Boolean): MapPack = synchronized(directoryLock) {
        ensureDirectory()
        MapPacks.choose(manifest, lite, freeBytes())
    }

    /** Downloads [pack] from [release], verifies it and installs it. */
    suspend fun download(release: MapsRelease, manifest: MapsManifest, pack: MapPack): InstalledPack = operation { callerContext ->
        var part: File? = null
        try {
            callerContext.ensureActive()
            val epoch = prepareDirectory()
            val url = release.assets[pack.name] ?: throw MapPackException(PackState.Reason.NO_RELEASE, "pack missing from release")
            if (!safeName(pack.name)) fail(PackState.Reason.NOT_A_MAP, "unsafe pack name")
            requireSpace(pack.bytes)
            val staging = File.createTempFile(STAGING_PREFIX, ".part", dir).also { part = it }
            mutableState.value = PackState.Downloading(pack, 0, pack.bytes)
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            get(url).use { input ->
                FileOutputStream(staging).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        callerContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        callerContext.ensureActive()
                        if (n > pack.bytes - done) fail(PackState.Reason.CHECKSUM, "pack is bigger than the manifest says")
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        mutableState.value = PackState.Downloading(pack, done, pack.bytes)
                    }
                    callerContext.ensureActive()
                    out.fd.sync()
                }
            }
            val sha = Hex.encode(digest.digest())
            if (done != pack.bytes || sha != pack.sha256.lowercase()) fail(PackState.Reason.CHECKSUM, "checksum mismatch")
            install(staging, pack.name, sha, manifest.sourceDate, epoch, callerContext)
        } catch (e: CancellationException) {
            mutableState.value = PackState.Idle(installed())
            throw e
        } catch (e: MapPackException) {
            mutableState.value = PackState.Failed(e.reason, installed())
            throw e
        } catch (e: IOException) {
            mutableState.value = PackState.Failed(PackState.Reason.OFFLINE, installed())
            throw MapPackException(PackState.Reason.OFFLINE, "download failed", e)
        } finally { part?.let { runCatching { it.delete() } } }
    }

    /**
     * Installs a pack file passed phone to phone. It must be a PMTiles vector archive covering Dhaka; when
     * [expectedSha256] is known (from a manifest) it must match too.
     */
    suspend fun import(file: File, name: String = MapPacks.DETAILED, expectedSha256: String? = null): InstalledPack = operation { callerContext ->
        var part: File? = null
        try {
            callerContext.ensureActive()
            val epoch = prepareDirectory(file)
            if (!file.isFile) fail(PackState.Reason.NOT_A_MAP, "not a file")
            val bytes = file.length()
            requireSpace(bytes)
            val safeName = name.takeIf(::safeName) ?: MapPacks.DETAILED
            val staging = File.createTempFile(STAGING_PREFIX, ".part", dir).also { part = it }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            file.inputStream().use { input ->
                FileOutputStream(staging).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        callerContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        callerContext.ensureActive()
                        if (n > bytes - done) fail(PackState.Reason.CHECKSUM, "source grew during import")
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                    }
                    callerContext.ensureActive()
                    out.fd.sync()
                }
            }
            val sha = Hex.encode(digest.digest())
            if (done != bytes || (expectedSha256 != null && sha != expectedSha256.lowercase())) {
                fail(PackState.Reason.CHECKSUM, "checksum mismatch")
            }
            install(staging, safeName, sha, "", epoch, callerContext)
        } catch (e: CancellationException) {
            mutableState.value = PackState.Idle(installed())
            throw e
        } catch (e: MapPackException) {
            mutableState.value = PackState.Failed(e.reason, installed())
            throw e
        } catch (e: IOException) {
            mutableState.value = PackState.Failed(PackState.Reason.OFFLINE, installed())
            throw MapPackException(PackState.Reason.OFFLINE, "import failed", e)
        } finally { part?.let { runCatching { it.delete() } } }
    }

    fun delete() = synchronized(directoryLock) {
        directoryLock.generation++
        dir.listFiles()?.forEach { it.delete() }
        mutableState.value = PackState.Idle(null)
    }

    private suspend fun <T> operation(block: suspend (CoroutineContext) -> T): T = directoryLock.operations.withLock {
        val callerContext = coroutineContext
        // Honor caller cancellation until commit, but never report prompt cancellation after deleting old maps.
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { block(callerContext) }
        }
    }

    private fun install(part: File, name: String, sha: String, sourceDate: String, epoch: Long, context: CoroutineContext): InstalledPack {
        context.ensureActive()
        val header = try {
            PmTilesReader.open(part).use { it.header }
        } catch (e: IOException) {
            fail(PackState.Reason.NOT_A_MAP, "not a PMTiles archive")
        }
        if (header.tileType != PmTileType.MVT || !header.contains(23.81, 90.41)) {
            fail(PackState.Reason.NOT_A_MAP, "not a Dhaka vector pack")
        }
        val target = File(dir, "${name.removeSuffix(".pmtiles")}-${UUID.randomUUID()}.pmtiles")
        val installed = InstalledPack(name, header.maxZoom, part.length(), sha, sourceDate, target.absolutePath)
        val info = File.createTempFile(STAGING_PREFIX, ".part", dir)
        var committed = false
        try {
            FileOutputStream(info).use {
                it.write(json.encodeToString(InstalledPack.serializer(), installed).encodeToByteArray())
                it.fd.sync()
            }
            synchronized(directoryLock) {
                context.ensureActive()
                if (directoryLock.generation != epoch) throw CancellationException("maps deleted during installation")
                Files.move(part.toPath(), target.toPath(), ATOMIC_MOVE)
                syncDirectory()
                context.ensureActive()
                // This atomic pointer update is the commit point. Old maps remain intact until then.
                Files.move(info.toPath(), File(dir, INFO).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                committed = true
                syncDirectory()
                // Cleanup is best effort: a locked old map must not turn a committed install into failure.
                runCatching { dir.listFiles()?.filter { it.name.endsWith(".pmtiles") && it != target }?.forEach { it.delete() } }
                syncDirectory()
                mutableState.value = PackState.Idle(installed)
            }
            return installed
        } finally {
            runCatching { info.delete() }
            if (!committed) runCatching { target.delete() }
        }
    }

    private fun readInstalled(): InstalledPack? = synchronized(directoryLock) {
        val info = File(dir, INFO)
        val pack = runCatching { json.decodeFromString(InstalledPack.serializer(), info.readText()) }.getOrNull() ?: return@synchronized null
        pack.takeIf { File(it.path).isFile }
    }

    private fun ensureDirectory() {
        if ((!dir.isDirectory && !dir.mkdirs()) || !dir.isDirectory) fail(PackState.Reason.NO_SPACE, "cannot create maps directory")
    }

    private fun prepareDirectory(source: File? = null): Long = synchronized(directoryLock) {
        ensureDirectory()
        // No other installer for this directory is active while operations is held. Keep an import source.
        val keep = source?.canonicalFile
        dir.listFiles()?.filter { it.name.startsWith(STAGING_PREFIX) && it.name.endsWith(".part") && it.canonicalFile != keep }
            ?.forEach { it.delete() }
        directoryLock.generation
    }

    private fun requireSpace(bytes: Long) {
        val free = freeBytes()
        if (bytes < 0 || free < 1_000_000 || bytes > free - 1_000_000) fail(PackState.Reason.NO_SPACE, "not enough space")
    }

    private fun safeName(name: String) = name.matches(Regex("""[a-z0-9-]+\.pmtiles"""))

    private fun syncDirectory() {
        // Directory channels are unavailable on some platforms (notably Windows); file data is fsynced above.
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun get(url: String): InputStream {
        val response = try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json, */*").build()).execute()
        } catch (e: IOException) {
            throw MapPackException(PackState.Reason.OFFLINE, "network", e)
        }
        if (!response.isSuccessful) {
            response.close()
            throw MapPackException(if (response.code == 404) PackState.Reason.NO_RELEASE else PackState.Reason.OFFLINE, "HTTP ${response.code}")
        }
        return response.body!!.byteStream()
    }

    private fun fail(reason: PackState.Reason, message: String): Nothing = throw MapPackException(reason, message)

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/tanzir71/harkara/releases?per_page=30"
        private const val INFO = "pack.json"
        private const val STAGING_PREFIX = ".map-pack-"
        private val locks = ConcurrentHashMap<String, DirectoryLock>()
    }

    private class DirectoryLock(val operations: Mutex = Mutex(), var generation: Long = 0)
}

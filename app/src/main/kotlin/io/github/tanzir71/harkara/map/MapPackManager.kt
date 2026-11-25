package io.github.tanzir71.harkara.map

import io.github.tanzir71.harkara.core.map.MapPack
import io.github.tanzir71.harkara.core.map.MapPacks
import io.github.tanzir71.harkara.core.map.MapsManifest
import io.github.tanzir71.harkara.core.map.MapsRelease
import io.github.tanzir71.harkara.core.map.PmTileType
import io.github.tanzir71.harkara.core.map.PmTilesReader
import io.github.tanzir71.harkara.core.nostr.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

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
    fun recommended(manifest: MapsManifest, lite: Boolean): MapPack = MapPacks.choose(manifest, lite, freeBytes())

    /** Downloads [pack] from [release], verifies it and installs it. */
    suspend fun download(release: MapsRelease, manifest: MapsManifest, pack: MapPack): InstalledPack = withContext(Dispatchers.IO) {
        val url = release.assets[pack.name] ?: throw MapPackException(PackState.Reason.NO_RELEASE, "pack missing from release")
        if (freeBytes() < pack.bytes + 1_000_000) fail(PackState.Reason.NO_SPACE, "not enough space")
        dir.mkdirs()
        val part = File(dir, pack.name + ".part")
        try {
            mutableState.value = PackState.Downloading(pack, 0, pack.bytes)
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            get(url).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > pack.bytes) fail(PackState.Reason.CHECKSUM, "pack is bigger than the manifest says")
                        mutableState.value = PackState.Downloading(pack, done, pack.bytes)
                    }
                }
            }
            val sha = Hex.encode(digest.digest())
            if (done != pack.bytes || sha != pack.sha256.lowercase()) fail(PackState.Reason.CHECKSUM, "checksum mismatch")
            install(part, pack.name, sha, manifest.sourceDate)
        } catch (e: MapPackException) {
            part.delete()
            mutableState.value = PackState.Failed(e.reason, installed())
            throw e
        } catch (e: IOException) {
            part.delete()
            mutableState.value = PackState.Failed(PackState.Reason.OFFLINE, installed())
            throw MapPackException(PackState.Reason.OFFLINE, "download failed", e)
        }
    }

    /**
     * Installs a pack file passed phone to phone. It must be a PMTiles vector archive covering Dhaka; when
     * [expectedSha256] is known (from a manifest) it must match too.
     */
    suspend fun import(file: File, name: String = MapPacks.DETAILED, expectedSha256: String? = null): InstalledPack = withContext(Dispatchers.IO) {
        try {
            if (freeBytes() < file.length() + 1_000_000) fail(PackState.Reason.NO_SPACE, "not enough space")
            dir.mkdirs()
            val safeName = name.takeIf { it.matches(Regex("""[a-z0-9-]+\.pmtiles""")) } ?: MapPacks.DETAILED
            val part = File(dir, "$safeName.part")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                }
            }
            val sha = Hex.encode(digest.digest())
            if (expectedSha256 != null && sha != expectedSha256.lowercase()) {
                part.delete()
                fail(PackState.Reason.CHECKSUM, "checksum mismatch")
            }
            install(part, safeName, sha, "")
        } catch (e: MapPackException) {
            mutableState.value = PackState.Failed(e.reason, installed())
            throw e
        }
    }

    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
        mutableState.value = PackState.Idle(null)
    }

    private fun install(part: File, name: String, sha: String, sourceDate: String): InstalledPack {
        val header = try {
            PmTilesReader.open(part).use { it.header }
        } catch (e: IOException) {
            part.delete()
            fail(PackState.Reason.NOT_A_MAP, "not a PMTiles archive")
        }
        if (header.tileType != PmTileType.MVT || !header.contains(23.81, 90.41)) {
            part.delete()
            fail(PackState.Reason.NOT_A_MAP, "not a Dhaka vector pack")
        }
        val target = File(dir, name)
        dir.listFiles()?.filter { it.name.endsWith(".pmtiles") }?.forEach { it.delete() }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        val installed = InstalledPack(name, header.maxZoom, target.length(), sha, sourceDate, target.absolutePath)
        File(dir, INFO).writeText(json.encodeToString(InstalledPack.serializer(), installed))
        mutableState.value = PackState.Idle(installed)
        return installed
    }

    private fun readInstalled(): InstalledPack? {
        val info = File(dir, INFO)
        val pack = runCatching { json.decodeFromString(InstalledPack.serializer(), info.readText()) }.getOrNull() ?: return null
        return pack.takeIf { File(it.path).isFile }
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
    }
}

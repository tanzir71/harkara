package io.github.tanzir71.harkara.update

import io.github.tanzir71.harkara.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** `latest.json` published with every release (handover §8.4). */
@Serializable
data class LatestRelease(
    val version: String,
    val versionCode: Int,
    val published: String = "",
    val apks: List<LatestApk> = emptyList(),
    val notes: String = "",
)

@Serializable
data class LatestApk(val abi: String, val url: String, val bytes: Long = 0, val sha256: String = "")

/**
 * Reads `latest.json` at most once a day while online and offers an update card when a newer
 * versionCode exists. Android itself enforces that an update carries the same signing key.
 */
class UpdateChecker(
    private val settings: AppSettings,
    private val currentVersionCode: Int,
    private val url: String = DEFAULT_URL,
    private val client: OkHttpClient = defaultClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableAvailable = MutableStateFlow(cached()?.takeIf { it.versionCode > currentVersionCode })

    /** The newer release, if one is known. */
    val available: StateFlow<LatestRelease?> = mutableAvailable.asStateFlow()

    /** Fetches `latest.json` unless it was fetched in the last 24 h. Returns the newer release, if any. */
    suspend fun checkIfDue(): LatestRelease? {
        val last = settings.getLong(KEY_LAST_CHECK)
        if (last != 0L && now() - last < ONE_DAY_MS) return mutableAvailable.value
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) null else json.decodeFromString(LatestRelease.serializer(), response.body!!.string())
                }
            }.getOrNull()
        }
        settings.putLong(KEY_LAST_CHECK, now())
        if (fetched != null) settings.putString(KEY_CACHED, json.encodeToString(LatestRelease.serializer(), fetched))
        val newer = (fetched ?: cached())?.takeIf { it.versionCode > currentVersionCode }
        mutableAvailable.value = newer
        return newer
    }

    private fun cached(): LatestRelease? = settings.getString(KEY_CACHED)?.let {
        runCatching { json.decodeFromString(LatestRelease.serializer(), it) }.getOrNull()
    }

    companion object {
        const val DEFAULT_URL = "https://tanzir71.github.io/harkara/latest.json"
        const val DOWNLOAD_PAGE = "https://tanzir71.github.io/harkara/"
        private const val KEY_LAST_CHECK = "update.last_check"
        private const val KEY_CACHED = "update.cached"
        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultClient by lazy {
            OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        }
    }
}

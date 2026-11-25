package io.github.tanzir71.harkara.core.map

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One downloadable pack from `maps.json`. */
@Serializable
data class MapPack(val name: String, val maxzoom: Int, val bytes: Long, val sha256: String)

/** `maps.json` written by `.github/workflows/mappack.yml` next to the packs in a `maps-YYYYMM` release. */
@Serializable
data class MapsManifest(
    val source: String = "",
    val sourceDate: String = "",
    val bbox: List<Double> = emptyList(),
    val attribution: String = "",
    val packs: List<MapPack> = emptyList(),
)

/** A `maps-YYYYMM` release and where its files are. */
data class MapsRelease(val tag: String, val assets: Map<String, String>) {
    val manifestUrl: String? get() = assets[MapPacks.MANIFEST]
}

/** Choosing and checking map packs; the download itself lives in the app. */
object MapPacks {
    const val MANIFEST = "maps.json"
    const val DETAILED = "dhaka-z15.pmtiles"
    const val SCHEMATIC = "dhaka-z13.pmtiles"
    private val TAG = Regex("""maps-(\d{6})""")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val json = Json { ignoreUnknownKeys = true }

    fun parseManifest(text: String): MapsManifest {
        val m = json.decodeFromString(MapsManifest.serializer(), text)
        require(m.packs.isNotEmpty()) { "manifest lists no packs" }
        m.packs.forEach { p ->
            require(p.name.matches(Regex("""[a-z0-9-]+\.pmtiles""")) && p.bytes > 0 && SHA256.matches(p.sha256.lowercase())) { "bad pack entry ${p.name}" }
        }
        return m
    }

    /** The newest `maps-YYYYMM` release in a GitHub `GET /repos/{owner}/{repo}/releases` answer. */
    fun newestRelease(releasesJson: String): MapsRelease? {
        val list = runCatching { json.parseToJsonElement(releasesJson) as JsonArray }.getOrNull() ?: return null
        return list.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            if (o["draft"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val month = TAG.matchEntire(tag)?.groupValues?.get(1) ?: return@mapNotNull null
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                val ao = a as? JsonObject ?: return@mapNotNull null
                val name = ao["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = ao["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                name to url
            }.toMap()
            if (MANIFEST !in assets) return@mapNotNull null
            month to MapsRelease(tag, assets)
        }.maxByOrNull { it.first }?.second
    }

    /**
     * The detailed z15 pack, unless Lite mode is on or the phone is short on space (less than three times
     * the pack free), then the small z13 schematic pack.
     */
    fun choose(manifest: MapsManifest, lite: Boolean, freeBytes: Long): MapPack {
        val detailed = manifest.packs.firstOrNull { it.name == DETAILED } ?: manifest.packs.maxBy { it.maxzoom }
        val schematic = manifest.packs.firstOrNull { it.name == SCHEMATIC } ?: manifest.packs.minBy { it.bytes }
        return if (lite || freeBytes < detailed.bytes * 3) schematic else detailed
    }
}

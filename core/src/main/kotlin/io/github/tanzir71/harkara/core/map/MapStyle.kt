package io.github.tanzir71.harkara.core.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Map colours, taken from the map colour and text tokens of one theme mode (hex `#RRGGBB`). */
data class MapColors(
    val land: String,
    val road: String,
    val water: String,
    val park: String,
    val block: String,
    val label: String,
    val labelHalo: String,
    val roadCasing: String,
)

/**
 * Builds the MapLibre style JSON for the offline Dhaka pack (Protomaps basemap schema v4: `earth`,
 * `water`, `landuse`, `buildings`, `roads`, `places`). Labels prefer Bangla (`name:bn`) and fall back to
 * `name`. The tile source is a local URL template served from the PMTiles file on the phone, so nothing
 * here touches the network.
 */
object MapStyle {
    const val SOURCE = "protomaps"
    const val ATTRIBUTION = "© OpenStreetMap contributors · Protomaps"

    fun build(colors: MapColors, tilesUrl: String, glyphsUrl: String, maxZoom: Int = 15, night: Boolean = false): JsonObject = buildJsonObject {
        put("version", 8)
        put("name", if (night) "Harkara Night" else "Harkara Day")
        put("glyphs", glyphsUrl)
        putJsonObject("sources") {
            putJsonObject(SOURCE) {
                put("type", "vector")
                putJsonArray("tiles") { add(JsonPrimitive(tilesUrl)) }
                put("minzoom", 0)
                put("maxzoom", maxZoom)
                put("attribution", ATTRIBUTION)
                putJsonArray("bounds") { DHAKA_BBOX.forEach { add(JsonPrimitive(it)) } }
            }
        }
        put("layers", layers(colors))
    }

    /** Dhaka bbox of the map pack: west, south, east, north. */
    val DHAKA_BBOX = listOf(90.30, 23.65, 90.55, 23.92)

    private fun layers(c: MapColors): JsonArray = buildJsonArray {
        add(layer("background", "background", null, paint = obj("background-color" to c.land)))
        add(layer("earth", "fill", "earth", paint = obj("fill-color" to c.land)))
        add(
            layer(
                "park", "fill", "landuse",
                filter = inKind("park", "nature_reserve", "garden", "grass", "golf_course", "cemetery", "forest", "wood", "playground"),
                paint = obj("fill-color" to c.park),
            ),
        )
        add(layer("water", "fill", "water", paint = obj("fill-color" to c.water)))
        add(layer("buildings", "fill", "buildings", minZoom = 14, paint = obj("fill-color" to c.block)))
        listOf("minor_road" to 0.8, "major_road" to 1.6, "highway" to 2.4).forEach { (kind, width) ->
            add(
                layer(
                    "road-$kind-casing", "line", "roads", filter = inKind(kind),
                    layout = obj("line-cap" to "round", "line-join" to "round"),
                    paint = buildJsonObject {
                        put("line-color", c.roadCasing)
                        put("line-width", zoomWidth(width + 1.2))
                    },
                ),
            )
        }
        listOf("minor_road" to 0.8, "major_road" to 1.6, "highway" to 2.4).forEach { (kind, width) ->
            add(
                layer(
                    "road-$kind", "line", "roads", filter = inKind(kind),
                    layout = obj("line-cap" to "round", "line-join" to "round"),
                    paint = buildJsonObject {
                        put("line-color", c.road)
                        put("line-width", zoomWidth(width))
                    },
                ),
            )
        }
        add(
            layer(
                "road-labels", "symbol", "roads", minZoom = 14, filter = inKind("major_road", "highway", "minor_road"),
                layout = buildJsonObject {
                    put("symbol-placement", "line")
                    put("text-field", nameExpression())
                    putJsonArray("text-font") { add(JsonPrimitive(FONT)) }
                    put("text-size", 11)
                },
                paint = obj("text-color" to c.label, "text-halo-color" to c.labelHalo).let {
                    buildJsonObject { it.forEach { (k, v) -> put(k, v) }; put("text-halo-width", 1.5) }
                },
            ),
        )
        add(
            layer(
                "place-labels", "symbol", "places",
                layout = buildJsonObject {
                    put("text-field", nameExpression())
                    putJsonArray("text-font") { add(JsonPrimitive(FONT)) }
                    put("text-size", buildJsonArray { add(JsonPrimitive("interpolate")); add(buildJsonArray { add(JsonPrimitive("linear")) }); add(buildJsonArray { add(JsonPrimitive("zoom")) }); add(JsonPrimitive(10)); add(JsonPrimitive(11)); add(JsonPrimitive(15)); add(JsonPrimitive(14)) })
                    put("text-max-width", 8)
                },
                paint = buildJsonObject {
                    put("text-color", c.label)
                    put("text-halo-color", c.labelHalo)
                    put("text-halo-width", 1.5)
                },
            ),
        )
    }

    const val FONT = "Noto Sans Regular"

    /** `["coalesce", ["get","name:bn"], ["get","name"]]` — Bangla first. */
    fun nameExpression(): JsonArray = buildJsonArray {
        add(JsonPrimitive("coalesce"))
        add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("name:bn")) })
        add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("name")) })
    }

    private fun zoomWidth(base: Double): JsonArray = buildJsonArray {
        add(JsonPrimitive("interpolate"))
        add(buildJsonArray { add(JsonPrimitive("exponential")); add(JsonPrimitive(1.6)) })
        add(buildJsonArray { add(JsonPrimitive("zoom")) })
        add(JsonPrimitive(10)); add(JsonPrimitive(base * 0.4))
        add(JsonPrimitive(15)); add(JsonPrimitive(base * 4))
    }

    private fun inKind(vararg kinds: String): JsonArray = buildJsonArray {
        add(JsonPrimitive("in"))
        add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("kind")) })
        add(buildJsonArray { add(JsonPrimitive("literal")); add(JsonArray(kinds.map(::JsonPrimitive))) })
    }

    private fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun layer(
        id: String,
        type: String,
        sourceLayer: String?,
        minZoom: Int? = null,
        filter: JsonElement? = null,
        layout: JsonObject? = null,
        paint: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        if (sourceLayer != null) {
            put("source", SOURCE)
            put("source-layer", sourceLayer)
        }
        if (minZoom != null) put("minzoom", minZoom)
        if (filter != null) put("filter", filter)
        if (layout != null) put("layout", layout)
        if (paint != null) put("paint", paint)
    }
}

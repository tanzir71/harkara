package io.github.tanzir71.harkara.core.map

import io.github.tanzir71.harkara.core.feed.FeedPost
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.location.LivePerson
import io.github.tanzir71.harkara.core.pins.Pin
import io.github.tanzir71.harkara.core.tasks.TaskOffer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Layers the map can show; each has a chip. */
enum class MapLayer(val key: String) {
    POSTS("posts"), MY_PINS("my_pins"), PEOPLE("people"), TASKS("tasks"), NODES("nodes"), PLACES("places");

    companion object {
        val DEFAULT: Set<MapLayer> = entries.toSet()
    }
}

/** How a marker is drawn. */
enum class MarkerKind { POST, PIN, PERSON, TASK, NODE }

/**
 * One thing on the map. [color] names a design token (`category/<tag>`, `state/online`, `state/node`,
 * `ink`), so the UI never invents a colour.
 */
data class MapMarker(
    val id: String,
    val layer: MapLayer,
    val kind: MarkerKind,
    val position: LatLng,
    val color: String,
    val label: String? = null,
    val detail: String? = null,
)

/** A neighbourhood node someone announced (M6), shown on the Nodes layer. */
data class MapNode(val id: String, val name: String, val position: LatLng)

/**
 * Builds the map's layers from what the phone holds: posts (category colour), places (other people's
 * shared and public pins), my pins, people sharing with me (initials + time left), tasks (price label)
 * and nodes. Markers are drawn as Compose overlays above the map surface; [featureCollection] gives the
 * same data as GeoJSON for export and for native map layers.
 */
object MapLayers {
    /** Posts: [MapMarker.label] is the category, [MapMarker.detail] the start of the text. */
    fun posts(posts: List<FeedPost>): List<MapMarker> = posts.map {
        MapMarker(
            "post:${it.id}", MapLayer.POSTS, MarkerKind.POST, it.location, "category/" + (it.category?.tag ?: "chatter"),
            it.category?.label, it.text.lineSequence().first().take(80),
        )
    }

    fun pins(pins: List<Pin>, me: String?): List<MapMarker> = pins.map {
        val mine = it.owner == me
        MapMarker(
            "pin:${it.address}", if (mine) MapLayer.MY_PINS else MapLayer.PLACES, MarkerKind.PIN, it.position,
            "category/" + it.color.tag, it.title,
        )
    }

    /** People with a position; [detail] is "<name> · <n> min left" (the UI formats the minutes itself). */
    fun people(people: List<LivePerson>, now: Long): List<MapMarker> = people.filter { it.position != null && !it.stopped && it.end > now }.map {
        val name = it.name?.takeIf { n -> n.isNotBlank() } ?: it.owner.take(8)
        MapMarker("person:${it.owner}", MapLayer.PEOPLE, MarkerKind.PERSON, it.position!!, "state/online", initials(name), name)
    }

    fun tasks(offers: List<TaskOffer>): List<MapMarker> = offers.mapNotNull { o ->
        val cell = o.geohash?.takeIf { GeoHash.isValid(it) } ?: return@mapNotNull null
        MapMarker("task:${o.address}", MapLayer.TASKS, MarkerKind.TASK, GeoMath.decode(cell), "ink", "৳" + o.price, o.title)
    }

    fun nodes(nodes: List<MapNode>): List<MapMarker> = nodes.map {
        MapMarker("node:${it.id}", MapLayer.NODES, MarkerKind.NODE, it.position, "state/node", null, it.name)
    }

    /** Draw order: posts below pins below tasks below nodes below people. */
    private val ORDER = listOf(MarkerKind.POST, MarkerKind.PIN, MarkerKind.TASK, MarkerKind.NODE, MarkerKind.PERSON)

    /** Everything on the [enabled] layers, in draw order. */
    fun build(
        enabled: Set<MapLayer>,
        posts: List<FeedPost> = emptyList(),
        pins: List<Pin> = emptyList(),
        me: String? = null,
        people: List<LivePerson> = emptyList(),
        tasks: List<TaskOffer> = emptyList(),
        nodes: List<MapNode> = emptyList(),
        now: Long,
    ): List<MapMarker> = visible(posts(posts) + pins(pins, me) + people(people, now) + tasks(tasks) + nodes(nodes), enabled)

    fun visible(markers: List<MapMarker>, enabled: Set<MapLayer>): List<MapMarker> =
        markers.filter { it.layer in enabled }.sortedBy { ORDER.indexOf(it.kind) }

    /** How many markers each layer has (for chip counts and the Lite list). */
    fun counts(markers: List<MapMarker>): Map<MapLayer, Int> = markers.groupingBy { it.layer }.eachCount()

    fun featureCollection(markers: List<MapMarker>): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            markers.forEach { m ->
                add(
                    buildJsonObject {
                        put("type", "Feature")
                        put("id", m.id)
                        putJsonObject("geometry") {
                            put("type", "Point")
                            putJsonArray("coordinates") { add(JsonPrimitive(m.position.lng)); add(JsonPrimitive(m.position.lat)) }
                        }
                        putJsonObject("properties") {
                            put("layer", m.layer.key)
                            put("kind", m.kind.name.lowercase())
                            put("color", m.color)
                            m.label?.let { put("label", it) }
                            m.detail?.let { put("detail", it) }
                        }
                    },
                )
            }
        }
    }

    /** "Rafi Hasan" -> "RH", "Rafi" -> "RA". */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val raw = if (words.size >= 2) "${words[0].first()}${words[1].first()}" else name.trim().take(2)
        return raw.uppercase()
    }
}

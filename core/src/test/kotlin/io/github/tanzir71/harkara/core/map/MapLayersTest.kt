package io.github.tanzir71.harkara.core.map

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.feed.FeedPost
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.location.LivePerson
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.pins.Pin
import io.github.tanzir71.harkara.core.pins.PinVisibility
import io.github.tanzir71.harkara.core.tasks.TaskOffers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapLayersTest {
    private val now = 1_764_000_000L
    private val me = KeyPair.generate()
    private val other = KeyPair.generate()
    private val lake = LatLng(23.7465, 90.3760)

    private val post = FeedPost.from(
        KeyPair.generate().signEvent(Kinds.FEED_POST, listOf(listOf("g", GeoHash.encode(23.7440, 90.3725, 7)), listOf("t", "traffic")), "Gridlock", now),
    )!!
    private fun pin(owner: KeyPair, id: String, title: String, color: Category) =
        Pin(id, owner.pubkeyHex, PinVisibility.PUBLIC, title, "", lake, color, now)
    private val myPin = pin(me, "a", "Home", Category.FOOD)
    private val water = pin(other, "w", "Water point", Category.STOCK)
    private val rafi = LivePerson(other.pubkeyHex, "s1", "Rafi", now + 38 * 60, LatLng(23.7430, 90.3700), 10, now, false)
    private val ended = rafi.copy(owner = "x", end = now - 1)
    private val waiting = rafi.copy(owner = "y", position = null)
    private val task = TaskOffers.parse(TaskOffers.build(other, "t1", "Medicine pickup", "pickup", 120, 23.7450, 90.3800, "", now))!!
    private val node = MapNode("n1", "Road 27 node", LatLng(23.7480, 90.3690))

    private fun all(enabled: Set<MapLayer> = MapLayer.DEFAULT) =
        MapLayers.build(enabled, listOf(post), listOf(myPin, water), me.pubkeyHex, listOf(rafi, ended, waiting), listOf(task), listOf(node), now)

    @Test
    fun `every layer builds its markers with token colours and labels`() {
        val markers = all()
        val byLayer = markers.groupBy { it.layer }
        assertEquals("category/traffic", byLayer.getValue(MapLayer.POSTS).single().color)
        assertEquals("Home", byLayer.getValue(MapLayer.MY_PINS).single().label)
        assertEquals("category/food", byLayer.getValue(MapLayer.MY_PINS).single().color)
        assertEquals("Water point", byLayer.getValue(MapLayer.PLACES).single().label)
        val person = byLayer.getValue(MapLayer.PEOPLE).single()
        assertEquals("RA", person.label)
        assertEquals("Rafi", person.detail)
        assertEquals("state/online", person.color)
        val t = byLayer.getValue(MapLayer.TASKS).single()
        assertEquals("৳120", t.label)
        assertTrue(GeoMath.distanceMeters(t.position, LatLng(23.7450, 90.3800)) < 100)
        assertEquals("state/node", byLayer.getValue(MapLayer.NODES).single().color)
        assertEquals(6, markers.size, "ended shares and people with no fix yet are left out")
        // Draw order: posts first, people last.
        assertEquals(MarkerKind.POST, markers.first().kind)
        assertEquals(MarkerKind.PERSON, markers.last().kind)
        assertEquals(1, MapLayers.counts(markers)[MapLayer.TASKS])
    }

    @Test
    fun `chips switch layers off`() {
        val noTasks = all(MapLayer.DEFAULT - MapLayer.TASKS - MapLayer.POSTS)
        assertTrue(noTasks.none { it.layer == MapLayer.TASKS || it.layer == MapLayer.POSTS })
        assertEquals(4, noTasks.size)
        assertTrue(all(emptySet()).isEmpty())
        assertEquals(listOf(MapLayer.PEOPLE), all(setOf(MapLayer.PEOPLE)).map { it.layer })
    }

    @Test
    fun `geojson feature collection`() {
        val fc = MapLayers.featureCollection(all())
        assertEquals("FeatureCollection", fc["type"]!!.jsonPrimitive.content)
        val features = fc["features"]!!.jsonArray.map { it as JsonObject }
        assertEquals(6, features.size)
        val task = features.single { it["properties"]!!.jsonObject["layer"]!!.jsonPrimitive.content == "tasks" }
        assertEquals("৳120", task["properties"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        val coords = features.single { it["id"]!!.jsonPrimitive.content == "node:n1" }["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(90.3690, coords[0].jsonPrimitive.double, 1e-9)
        assertEquals(23.7480, coords[1].jsonPrimitive.double, 1e-9)
        assertEquals("RH", MapLayers.initials("rafi hasan"))
    }

    @Test
    fun `map pack manifest and release selection`() {
        val manifest = MapPacks.parseManifest(
            """{"source":"https://build.protomaps.com/20261124.pmtiles","sourceDate":"20261124","bbox":[90.30,23.65,90.55,23.92],"attribution":"© OpenStreetMap contributors · Protomaps",
            "packs":[{"name":"dhaka-z15.pmtiles","maxzoom":15,"bytes":22789276,"sha256":"8de855863e31e502917656701da314314e2cb92f881ef9a3ea1c6b19c0d94e5d"},
                     {"name":"dhaka-z13.pmtiles","maxzoom":13,"bytes":2804192,"sha256":"882fcc9a7a464b3f7d9bca13f28a6aed16dda7c6abc3b4a9a454ad5476a1a07a"}]}""",
        )
        assertEquals(MapPacks.DETAILED, MapPacks.choose(manifest, lite = false, freeBytes = 2_000_000_000).name)
        assertEquals(MapPacks.SCHEMATIC, MapPacks.choose(manifest, lite = true, freeBytes = 2_000_000_000).name)
        assertEquals(MapPacks.SCHEMATIC, MapPacks.choose(manifest, lite = false, freeBytes = 50_000_000).name)
        val releases = """[
          {"tag_name":"v0.3.0","draft":false,"assets":[{"name":"app.apk","browser_download_url":"https://x/app.apk"}]},
          {"tag_name":"maps-202610","draft":false,"assets":[{"name":"maps.json","browser_download_url":"https://x/10/maps.json"}]},
          {"tag_name":"maps-202611","draft":false,"assets":[{"name":"maps.json","browser_download_url":"https://x/11/maps.json"},{"name":"dhaka-z15.pmtiles","browser_download_url":"https://x/11/dhaka-z15.pmtiles"}]},
          {"tag_name":"maps-202612","draft":true,"assets":[{"name":"maps.json","browser_download_url":"https://x/12/maps.json"}]}
        ]"""
        val newest = MapPacks.newestRelease(releases)!!
        assertEquals("maps-202611", newest.tag)
        assertEquals("https://x/11/maps.json", newest.manifestUrl)
        assertEquals("https://x/11/dhaka-z15.pmtiles", newest.assets["dhaka-z15.pmtiles"])
        assertEquals(null, MapPacks.newestRelease("""[{"tag_name":"v1"}]"""))
        assertTrue(runCatching { MapPacks.parseManifest("""{"packs":[{"name":"../evil","maxzoom":1,"bytes":1,"sha256":"00"}]}""") }.isFailure)
    }
}

package io.github.tanzir71.harkara.core.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapStyleTest {
    private val day = MapColors("#ECE8DF", "#FFFFFF", "#CFE1E8", "#DCE6D2", "#E2DDD2", "#18191C", "#FFFFFF", "#E2DED5")
    private val night = MapColors("#1A1C1F", "#2C2F34", "#142730", "#18241B", "#212327", "#F3F1EC", "#0E0F11", "#33363B")

    private fun layers(style: JsonObject) = style["layers"]!!.jsonArray.map { it as JsonObject }
    private fun layer(style: JsonObject, id: String) = layers(style).single { it["id"]!!.jsonPrimitive.content == id }

    @Test
    fun `style uses token colours, a local tile source and Bangla-first labels`() {
        val style = MapStyle.build(day, "http://127.0.0.1:8765/tiles/{z}/{x}/{y}.mvt", "http://127.0.0.1:8765/glyphs/{fontstack}/{range}.pbf")
        assertEquals(8, style["version"]!!.jsonPrimitive.content.toInt())
        val source = style["sources"]!!.jsonObject[MapStyle.SOURCE]!!.jsonObject
        assertTrue(source["tiles"]!!.jsonArray.single().jsonPrimitive.content.startsWith("http://127.0.0.1"))
        assertEquals(MapStyle.ATTRIBUTION, source["attribution"]!!.jsonPrimitive.content)
        val ids = layers(style).map { it["id"]!!.jsonPrimitive.content }
        assertTrue(ids.containsAll(listOf("background", "water", "park", "buildings", "road-major_road", "water-labels", "place-labels")))
        fun paint(id: String, key: String) = layer(style, id)["paint"]!!.jsonObject[key]!!.jsonPrimitive.content
        assertEquals("#CFE1E8", paint("water", "fill-color"))
        assertEquals("#DCE6D2", paint("park", "fill-color"))
        assertEquals("#ECE8DF", paint("background", "background-color"))
        assertEquals("#E2DDD2", paint("buildings", "fill-color"))
        assertEquals("#FFFFFF", paint("road-highway", "line-color"))
        assertEquals("#E2DED5", paint("road-highway-casing", "line-color"))
        val label = layer(style, "place-labels")["layout"]!!.jsonObject["text-field"] as JsonArray
        assertEquals("coalesce", label[0].jsonPrimitive.content)
        assertEquals("name:bn", label[1].jsonArray[1].jsonPrimitive.content)
        assertEquals("name", label[2].jsonArray[1].jsonPrimitive.content)
        assertTrue(style.toString().contains("90.3"))
        // Road and place labels draw before (under) nothing else: symbols come last.
        assertTrue(ids.indexOf("place-labels") > ids.indexOf("road-highway"))
    }

    @Test
    fun `night colours, English labels, the pmtiles file and bundled glyphs`() {
        val style = MapStyle.build(night, night = true, labels = LabelLanguage.ENGLISH, sourceUrl = MapStyle.pmtilesUrl("/data/user/0/io.github.tanzir71.harkara/files/maps/dhaka-z15.pmtiles"))
        assertEquals("Harkara Night", style["name"]!!.jsonPrimitive.content)
        assertEquals("asset://glyphs/{fontstack}/{range}.pbf", style["glyphs"]!!.jsonPrimitive.content)
        val source = style["sources"]!!.jsonObject[MapStyle.SOURCE]!!.jsonObject
        assertEquals("pmtiles://file:///data/user/0/io.github.tanzir71.harkara/files/maps/dhaka-z15.pmtiles", source["url"]!!.jsonPrimitive.content)
        assertNull(source["tiles"])
        assertEquals("#142730", layer(style, "water")["paint"]!!.jsonObject["fill-color"]!!.jsonPrimitive.content)
        assertEquals("#F3F1EC", layer(style, "road-labels")["paint"]!!.jsonObject["text-color"]!!.jsonPrimitive.content)
        val label = layer(style, "road-labels")["layout"]!!.jsonObject
        assertEquals("name:en", (label["text-field"] as JsonArray)[1].jsonArray[1].jsonPrimitive.content)
        assertEquals(MapStyle.FONT, label["text-font"]!!.jsonArray.single().jsonPrimitive.content)
        // Every layer with a source uses the one vector source, and every symbol layer uses the bundled font.
        layers(style).filter { it["source"] != null }.forEach { assertEquals(MapStyle.SOURCE, it["source"]!!.jsonPrimitive.content) }
        layers(style).filter { it["type"]!!.jsonPrimitive.content == "symbol" }.forEach {
            assertEquals(MapStyle.FONT, it["layout"]!!.jsonObject["text-font"]!!.jsonArray.single().jsonPrimitive.content)
        }
    }
}

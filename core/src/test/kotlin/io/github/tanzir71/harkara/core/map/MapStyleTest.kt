package io.github.tanzir71.harkara.core.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapStyleTest {
    private val day = MapColors("#ECE8DF", "#FFFFFF", "#CFE1E8", "#DCE6D2", "#E2DDD2", "#18191C", "#FFFFFF", "#E2DED5")

    @Test
    fun `style uses token colours, a local tile source and Bangla-first labels`() {
        val style = MapStyle.build(day, "http://127.0.0.1:8765/tiles/{z}/{x}/{y}.mvt", "http://127.0.0.1:8765/glyphs/{fontstack}/{range}.pbf")
        assertEquals(8, style["version"]!!.jsonPrimitive.content.toInt())
        val source = style["sources"]!!.jsonObject[MapStyle.SOURCE]!!.jsonObject
        assertTrue(source["tiles"]!!.jsonArray.single().jsonPrimitive.content.startsWith("http://127.0.0.1"))
        assertEquals(MapStyle.ATTRIBUTION, source["attribution"]!!.jsonPrimitive.content)
        val layers = style["layers"]!!.jsonArray.map { it as JsonObject }
        val ids = layers.map { it["id"]!!.jsonPrimitive.content }
        assertTrue(ids.containsAll(listOf("background", "water", "park", "buildings", "road-major_road", "place-labels")))
        fun paint(id: String, key: String) = layers.single { it["id"]!!.jsonPrimitive.content == id }["paint"]!!.jsonObject[key]!!.jsonPrimitive.content
        assertEquals("#CFE1E8", paint("water", "fill-color"))
        assertEquals("#DCE6D2", paint("park", "fill-color"))
        assertEquals("#ECE8DF", paint("background", "background-color"))
        val label = layers.single { it["id"]!!.jsonPrimitive.content == "place-labels" }["layout"]!!.jsonObject["text-field"] as JsonArray
        assertEquals("coalesce", label[0].jsonPrimitive.content)
        assertEquals("name:bn", label[1].jsonArray[1].jsonPrimitive.content)
        assertTrue(style.toString().contains("90.3"))
    }
}

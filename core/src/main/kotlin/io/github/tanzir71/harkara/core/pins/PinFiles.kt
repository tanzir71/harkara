package io.github.tanzir71.harkara.core.pins

import io.github.tanzir71.harkara.core.geo.LatLng
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A place read from a file: what becomes a new private pin. */
data class ImportedPlace(val title: String, val note: String, val position: LatLng)

enum class PinFileFormat(val extension: String, val mime: String) {
    GPX("gpx", "application/gpx+xml"),
    KML("kml", "application/vnd.google-earth.kml+xml"),
    GEOJSON("geojson", "application/geo+json");

    companion object {
        fun guess(name: String, text: String): PinFileFormat? {
            val lower = name.lowercase(Locale.ROOT)
            entries.firstOrNull { lower.endsWith("." + it.extension) }?.let { return it }
            if (lower.endsWith(".json")) return GEOJSON
            val head = text.trimStart().take(400)
            return when {
                head.startsWith("{") -> GEOJSON
                head.contains("<gpx") -> GPX
                head.contains("<kml") -> KML
                else -> null
            }
        }
    }
}

/**
 * Pin import/export as GPX 1.1 waypoints, KML placemarks or a GeoJSON FeatureCollection of points, so pins
 * move to and from OsmAnd, Organic Maps, Google My Maps and uMap. Only points are read; tracks and shapes
 * are skipped. XML is parsed with DTDs and external entities disabled.
 */
object PinFiles {
    const val MAX_IMPORT = 2_000

    fun export(pins: List<Pin>, format: PinFileFormat): String = when (format) {
        PinFileFormat.GPX -> gpx(pins)
        PinFileFormat.KML -> kml(pins)
        PinFileFormat.GEOJSON -> geoJson(pins).toString()
    }

    fun import(text: String, format: PinFileFormat): List<ImportedPlace> = when (format) {
        PinFileFormat.GPX -> readXml(text) { doc ->
            doc.getElementsByTagNameNS("*", "wpt").elements().mapNotNull { wpt ->
                val lat = wpt.getAttribute("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = wpt.getAttribute("lon").toDoubleOrNull() ?: return@mapNotNull null
                place(wpt.child("name"), wpt.child("desc") ?: wpt.child("cmt"), lat, lon)
            }
        }
        PinFileFormat.KML -> readXml(text) { doc ->
            doc.getElementsByTagNameNS("*", "Placemark").elements().mapNotNull { pm ->
                val point = pm.getElementsByTagNameNS("*", "Point").elements().firstOrNull() ?: return@mapNotNull null
                val coords = point.child("coordinates")?.trim()?.split(Regex("[,\\s]+")) ?: return@mapNotNull null
                val lon = coords.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = coords.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                place(pm.child("name"), pm.child("description"), lat, lon)
            }
        }
        PinFileFormat.GEOJSON -> readGeoJson(text)
    }.take(MAX_IMPORT)

    // ---- writers --------------------------------------------------------------------------------

    private fun gpx(pins: List<Pin>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Harkara" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        pins.forEach { p ->
            append("""  <wpt lat="${num(p.position.lat)}" lon="${num(p.position.lng)}">""")
            append("<name>").append(xml(p.title)).append("</name>")
            if (p.note.isNotBlank()) append("<desc>").append(xml(p.note)).append("</desc>")
            append("<type>").append(p.color.tag).append("</type>")
            append("</wpt>\n")
        }
        append("</gpx>\n")
    }

    private fun kml(pins: List<Pin>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Harkara pins</name>""").append('\n')
        pins.forEach { p ->
            append("  <Placemark><name>").append(xml(p.title)).append("</name>")
            if (p.note.isNotBlank()) append("<description>").append(xml(p.note)).append("</description>")
            append("<Point><coordinates>").append(num(p.position.lng)).append(',').append(num(p.position.lat)).append("</coordinates></Point></Placemark>\n")
        }
        append("</Document></kml>\n")
    }

    fun geoJson(pins: List<Pin>): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            pins.forEach { p ->
                add(
                    buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("geometry") {
                            put("type", "Point")
                            putJsonArray("coordinates") { add(JsonPrimitive(p.position.lng)); add(JsonPrimitive(p.position.lat)) }
                        }
                        putJsonObject("properties") {
                            put("name", p.title)
                            if (p.note.isNotBlank()) put("description", p.note)
                            put("color", p.color.tag)
                            put("visibility", p.visibility.wire)
                        }
                    },
                )
            }
        }
    }

    // ---- readers --------------------------------------------------------------------------------

    private fun readGeoJson(text: String): List<ImportedPlace> {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val features = when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "FeatureCollection" -> (root["features"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            "Feature" -> listOf(root)
            else -> emptyList()
        }
        return features.mapNotNull { f ->
            val geometry = f["geometry"] as? JsonObject ?: return@mapNotNull null
            if (geometry["type"]?.jsonPrimitive?.contentOrNull != "Point") return@mapNotNull null
            val coords = runCatching { geometry["coordinates"]!!.jsonArray }.getOrNull() ?: return@mapNotNull null
            val lng = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val props = f["properties"] as? JsonObject
            fun prop(vararg names: String) = names.firstNotNullOfOrNull { (props?.get(it) as? JsonPrimitive)?.contentOrNull }
            place(prop("name", "title"), prop("description", "desc", "note"), lat, lng)
        }
    }

    private fun place(name: String?, note: String?, lat: Double, lng: Double): ImportedPlace? {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: String.format(Locale.ROOT, "%.5f, %.5f", lat, lng)
        return ImportedPlace(title.take(Pins.MAX_TITLE), note?.trim().orEmpty().take(Pins.MAX_NOTE), LatLng(lat, lng))
    }

    private fun <T> readXml(text: String, block: (org.w3c.dom.Document) -> List<T>): List<T> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        if (text.contains("<!DOCTYPE", ignoreCase = true)) return emptyList()
        val doc = runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))) }.getOrNull() ?: return emptyList()
        return block(doc)
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.child(name: String): String? =
        (0 until childNodes.length).map { childNodes.item(it) }.firstOrNull { it is Element && (it.localName ?: it.nodeName) == name }?.textContent

    private fun num(v: Double) = String.format(Locale.ROOT, "%.6f", v)

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

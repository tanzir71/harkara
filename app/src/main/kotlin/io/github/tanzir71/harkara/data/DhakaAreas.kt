package io.github.tanzir71.harkara.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.cos

@Serializable
data class DhakaArea(val name: String, val lat: Double, val lng: Double)

@Serializable
private data class DhakaAreasFile(val areas: List<DhakaArea>)

/** Bundled Dhaka neighbourhoods (`data/dhaka-areas.json`, from loka). */
class DhakaAreas(val all: List<DhakaArea>) {
    /** Nearest named area to a coordinate (equirectangular distance is plenty inside one city). */
    fun nearest(lat: Double, lng: Double): DhakaArea = all.minBy { area ->
        val scale = cos(Math.toRadians((lat + area.lat) / 2))
        val dLat = lat - area.lat
        val dLng = (lng - area.lng) * scale
        dLat * dLat + dLng * dLng
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): DhakaAreas = DhakaAreas(json.decodeFromString(DhakaAreasFile.serializer(), text).areas)

        fun load(context: Context): DhakaAreas =
            parse(context.assets.open("dhaka-areas.json").bufferedReader().use { it.readText() })
    }
}

package br.com.palmasoffline

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.File
import java.net.URI
import java.time.LocalDate

interface SatelliteMapInfo {
    val title: String
    val bounds: LatLngBounds
    val attribution: String
    val licenseUrl: String
    val sourceUrl: String
    val displayDate: String
    val regional: Boolean
}

/** Completed package in app-owned storage; tiles are read directly from disk. */
data class OfflineSatellitePackage(
    override val title: String,
    override val bounds: LatLngBounds,
    override val attribution: String,
    override val licenseUrl: String,
    override val sourceUrl: String,
    val startDate: String,
    val endDate: String,
    val directory: File,
    val minZoom: Int,
    val maxZoom: Int,
    val tileSize: Int,
    val tileCount: Int,
    val tileBytes: Long,
) : SatelliteMapInfo {
    override val regional = true
    override val displayDate: String get() = if (startDate.take(4) == endDate.take(4)) startDate.take(4)
        else "${startDate.take(4)}–${endDate.take(4)}"
    val tileTemplate: String get() = Uri.fromFile(File(directory, "tiles")).toString() + "/{z}/{x}/{y}.png"

    fun source(id: String): RasterSource {
        val definition = TileSet("2.2.0", tileTemplate)
        definition.minZoom = minZoom.toFloat()
        definition.maxZoom = maxZoom.toFloat()
        definition.setBounds(bounds)
        definition.attribution = attribution
        definition.scheme = "xyz"
        return RasterSource(id, definition, tileSize)
    }

    companion object {
        const val DIRECTORY = "cbers8bpm-v1"

        fun directory(context: Context): File? = context.getExternalFilesDir("maps")?.let { File(it, DIRECTORY) }

        fun load(context: Context): OfflineSatellitePackage? = runCatching {
            val folder = directory(context) ?: return null
            val manifest = File(folder, "package.json")
            if (!manifest.isFile) return null
            require(manifest.length() in 1..2_000_000)
            val metadata = JSONObject(manifest.readText())
            require(metadata.getInt("schemaVersion") == 1 && metadata.getBoolean("complete"))
            require(metadata.getString("packageId") == DIRECTORY)
            require(metadata.getString("format") == "png" && metadata.getString("scheme") == "xyz")
            require(metadata.getInt("municipalityCount") == 11 && metadata.getBoolean("strictMunicipalityMask"))
            val values = metadata.getJSONArray("bounds")
            require(values.length() == 4)
            val west = values.getDouble(0)
            val south = values.getDouble(1)
            val east = values.getDouble(2)
            val north = values.getDouble(3)
            require(west < east && south < north &&
                BatalhaoRegion.contains(Coordinates(south, west)) &&
                BatalhaoRegion.contains(Coordinates(north, east)))
            val minimum = metadata.getInt("minZoom")
            val maximum = metadata.getInt("maxZoom")
            require(minimum in 0..20 && maximum in minimum..20 && metadata.getInt("tileSize") == 256)
            val count = metadata.getInt("tileCount")
            val bytes = metadata.getLong("tileBytes")
            require(count > 0 && bytes > 0)
            val start = metadata.getString("startDate").also { LocalDate.parse(it) }
            val end = metadata.getString("endDate").also { LocalDate.parse(it) }
            require(start <= end)
            fun link(key: String) = metadata.getString(key).also {
                val parsed = URI(it)
                require(parsed.scheme == "https" && !parsed.host.isNullOrBlank())
            }
            // The installer publishes package.json last, after transfer and checksum checks.
            val probes = metadata.getJSONArray("probes")
            require(probes.length() >= 3)
            for (index in 0 until probes.length()) {
                val probe = probes.getJSONObject(index)
                val path = probe.getString("path")
                require(path.matches(Regex("tiles/\\d+/\\d+/\\d+\\.png")))
                require(File(folder, path).length() == probe.getLong("bytes"))
            }
            OfflineSatellitePackage(metadata.getString("title"), LatLngBounds.from(north, east, south, west),
                metadata.getString("attribution"), link("licenseUrl"), link("sourceUrl"), start, end, folder,
                minimum, maximum, 256, count, bytes)
        }.getOrNull()
    }
}

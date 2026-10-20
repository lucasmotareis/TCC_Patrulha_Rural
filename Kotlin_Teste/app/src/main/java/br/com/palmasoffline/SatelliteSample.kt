package br.com.palmasoffline

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Optional, offline image already warped to Web Mercator by the package builder. */
data class SatelliteSample(
    override val title: String,
    val acquiredAt: String,
    override val bounds: LatLngBounds,
    val image: String,
    override val attribution: String,
    override val licenseUrl: String,
    override val sourceUrl: String,
) : SatelliteMapInfo {
    override val regional = false
    val assetUri get() = "asset://$ASSET_DIRECTORY/$image"
    override val displayDate: String get() = LocalDate.parse(acquiredAt.take(10))
        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    val corners get() = LatLngQuad(
        LatLng(bounds.latitudeNorth, bounds.longitudeWest),
        LatLng(bounds.latitudeNorth, bounds.longitudeEast),
        LatLng(bounds.latitudeSouth, bounds.longitudeEast),
        LatLng(bounds.latitudeSouth, bounds.longitudeWest),
    )

    companion object {
        private const val ASSET_DIRECTORY = "batalhao/satellite"

        /** Missing or unusable samples leave the vector map available. */
        fun load(context: Context): SatelliteSample? = runCatching {
            val metadata = context.assets.open("$ASSET_DIRECTORY/sample.json").bufferedReader().use {
                JSONObject(it.readText())
            }
            fun text(key: String): String = metadata.getString(key).trim().also { require(it.isNotEmpty()) }
            fun webLink(key: String): String = text(key).also {
                val uri = URI(it)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank())
            }
            val values = metadata.getJSONArray("bounds")
            require(values.length() == 4)
            val west = values.getDouble(0)
            val south = values.getDouble(1)
            val east = values.getDouble(2)
            val north = values.getDouble(3)
            require(west < east && south < north &&
                BatalhaoRegion.contains(Coordinates(south, west)) &&
                BatalhaoRegion.contains(Coordinates(north, east)))
            val image = text("image")
            require(image.matches(Regex("[A-Za-z0-9_-]+\\.(png|jpg|jpeg)")))
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open("$ASSET_DIRECTORY/$image").use {
                BitmapFactory.decodeStream(it, null, dimensions)
            }
            require(dimensions.outWidth in 1..4096 && dimensions.outHeight in 1..4096)
            val acquiredAt = text("acquiredAt")
            LocalDate.parse(acquiredAt.take(10))
            SatelliteSample(text("title"), acquiredAt, LatLngBounds.from(north, east, south, west),
                image, text("attribution"), webLink("licenseUrl"), webLink("sourceUrl"))
        }.getOrNull()
    }
}

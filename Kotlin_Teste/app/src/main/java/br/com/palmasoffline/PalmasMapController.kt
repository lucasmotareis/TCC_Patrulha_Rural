package br.com.palmasoffline

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import uniffi.ferrostar.UserLocation
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.URI

/** Owns the map renderer; all calls occur on the UI thread. No network sources. */
class PalmasMapController(val view: MapView, savedState: Bundle?, private val reportStatus: (String?) -> Unit) {
    var onPointSelected: (Coordinates) -> Unit = {}
    var onSettlementSelected: (SettlementDestination) -> Unit = {}
    val satelliteSample = SatelliteSample.load(view.context)
    val satelliteMap: SatelliteMapInfo? = OfflineSatellitePackage.load(view.context) ?: satelliteSample
    private var satelliteVisibility by mutableStateOf(
        satelliteMap != null && (savedState?.getBoolean(SATELLITE_STATE_KEY, true) ?: true)
    )
    val satelliteEnabled: Boolean get() = satelliteVisibility
    private var satelliteLayer: RasterLayer? = null
    private var satelliteLoadFailed = false
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var routeSource: GeoJsonSource? = null
    private var userLocation: UserLocation? = null
    private var wasNavigating = false
    private var following = true
    private var navigationSource: GeoJsonSource? = null
    private var navigationLayer: SymbolLayer? = null
    private var destroyed = false
    private var desired = MapData(null, null, null)
    private var rendered: MapData? = null
    private var needsInitialRegionFit = savedState == null
    private val bounds = LatLngBounds.from(BatalhaoRegion.NORTH, BatalhaoRegion.EAST, BatalhaoRegion.SOUTH, BatalhaoRegion.WEST)
    private val visualBounds: LatLngBounds by lazy {
        runCatching {
            val metadata = view.context.assets.open("batalhao/metadata.json").bufferedReader().use { JSONObject(it.readText()) }
            val values = metadata.getJSONArray("visualBounds")
            val west = values.getDouble(0)
            val south = values.getDouble(1)
            val east = values.getDouble(2)
            val north = values.getDouble(3)
            require(west < east && south < north &&
                BatalhaoRegion.contains(Coordinates(south, west)) &&
                BatalhaoRegion.contains(Coordinates(north, east)))
            LatLngBounds.from(north, east, south, west)
        }.getOrDefault(bounds)
    }

    init {
        view.onCreate(savedState)
        // Compose changes the map height when the route summary appears. Fit after
        // that resize, otherwise the native SurfaceView can cancel the earlier animation.
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) && style != null) {
                view.post {
                    if (!destroyed) {
                        if (wasNavigating && following) followPosition()
                        else if (!wasNavigating && desired.route != null) fitRoute()
                        else fitInitialRegion()
                    }
                }
            }
        }
        view.addOnDidFailLoadingMapListener { message ->
            if (!destroyed) reportStatus("Não foi possível abrir o mapa local: $message")
        }
        view.getMapAsync { readyMap ->
            if (!destroyed) {
                map = readyMap
                readyMap.setLatLngBoundsForCameraTarget(bounds)
                readyMap.setMinZoomPreference(7.0)
                readyMap.setMaxZoomPreference(19.0)
                readyMap.uiSettings.isCompassEnabled = false
                readyMap.uiSettings.isRotateGesturesEnabled = false
                readyMap.uiSettings.isTiltGesturesEnabled = false
                if (savedState == null) {
                    readyMap.cameraPosition = CameraPosition.Builder().target(visualBounds.center).zoom(7.0).build()
                }
                readyMap.addOnCameraMoveStartedListener { reason -> if (reason == 1) following = false }
                readyMap.addOnMapClickListener { location ->
                    val screenPoint = readyMap.projection.toScreenLocation(location)
                    val radius = 20 * view.resources.displayMetrics.density
                    val settlement = readyMap.queryRenderedFeatures(
                        RectF(screenPoint.x - radius, screenPoint.y - radius, screenPoint.x + radius, screenPoint.y + radius),
                        "settlement-points", "settlement-labels",
                    ).mapNotNull { SettlementDestination.fromFeature(it) }.minByOrNull {
                        val position = readyMap.projection.toScreenLocation(LatLng(it.coordinates.latitude, it.coordinates.longitude))
                        val dx = position.x - screenPoint.x
                        val dy = position.y - screenPoint.y
                        dx * dx + dy * dy
                    }
                    if (settlement != null) onSettlementSelected(settlement)
                    else {
                        val point = Coordinates(location.latitude, location.longitude)
                        if (BatalhaoRegion.contains(point)) onPointSelected(point)
                    }
                    true
                }
                readyMap.setStyle(Style.Builder().fromUri("asset://batalhao/style.json")) { loaded ->
                    if (!destroyed) {
                        style = loaded
                        installSatelliteSample(loaded)
                        routeSource = loaded.getSourceAs<GeoJsonSource>("route")
                        // Commit this small source before fitting the camera, then request a frame.
                        routeSource?.setOverrideSynchronousUpdate(true)
                        val positionSource = GeoJsonSource("navigation-position", emptyFeatures())
                        positionSource.setOverrideSynchronousUpdate(true)
                        loaded.addSource(positionSource)
                        loaded.addImage("navigation-arrow", navigationArrow())
                        val positionLayer = SymbolLayer("navigation-position-layer", "navigation-position")
                            .withProperties(iconImage("navigation-arrow"), iconSize(0.65f), iconAllowOverlap(true), iconIgnorePlacement(true), iconRotationAlignment("map"))
                        loaded.addLayer(positionLayer)
                        navigationSource = positionSource
                        navigationLayer = positionLayer
                        reportStatus(null)
                        render()
                        fitInitialRegion()
                    }
                }
            }
        }
    }

    fun update(origin: Coordinates?, destination: Coordinates?, route: RouteResult?) {
        desired = MapData(origin, destination, route)
        render()
    }

    private fun render() {
        val currentStyle = style ?: return
        val previous = rendered
        if (desired == previous) return
        if (desired.origin != previous?.origin) setPoint(currentStyle, "origin", desired.origin, "A")
        if (desired.destination != previous?.destination) setPoint(currentStyle, "destination", desired.destination, "B")
        if (desired.route !== previous?.route) {
            val points = desired.route?.geometry.orEmpty()
            val data = if (points.size < 2) emptyFeatures() else
                FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(
                    LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }))))
            routeSource?.setGeoJson(data)
            rendered = desired
            if (points.size >= 2 && !wasNavigating) fitRoute()
        } else rendered = desired
        // Source mutations do not necessarily invalidate Compose's native SurfaceView.
        map?.triggerRepaint()
    }

    private fun setPoint(style: Style, source: String, coordinates: Coordinates?, label: String) {
        val data = if (coordinates == null) emptyFeatures() else {
            val feature = Feature.fromGeometry(Point.fromLngLat(coordinates.longitude, coordinates.latitude))
            feature.addStringProperty("name", label)
            FeatureCollection.fromFeatures(listOf(feature))
        }
        style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(data)
    }

    fun updateNavigation(location: UserLocation?, active: Boolean) {
        if (active && !wasNavigating) following = true
        wasNavigating = active
        userLocation = location
        navigationSource?.setGeoJson(if (location == null) emptyFeatures() else
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(location.coordinates.lng, location.coordinates.lat)))))
        navigationLayer?.setProperties(iconRotate(location?.courseOverGround?.degrees?.toFloat() ?: 0f))
        if (active && following) followPosition()
        map?.triggerRepaint()
    }

    fun recenter() { following = true; followPosition() }

    private fun installSatelliteSample(loaded: Style) {
        val satellite = satelliteMap ?: return
        try {
            if (satellite is OfflineSatellitePackage) loaded.addSource(satellite.source("satellite-sample"))
            else satelliteSample?.let { loaded.addSource(ImageSource("satellite-sample", it.corners, URI(it.assetUri))) }
            val layer = RasterLayer("satellite-sample-raster", "satellite-sample").withProperties(
                rasterOpacity(1f), rasterFadeDuration(0f), rasterBrightnessMin(0.08f), rasterContrast(0.1f),
                visibility(if (satelliteEnabled) "visible" else "none"),
            )
            // Preserve roads, boundaries, settlement references and the active route above the image.
            loaded.addLayerBelow(layer, "streams")
            satelliteLayer = layer
        } catch (_: Exception) {
            satelliteLoadFailed = true
            satelliteVisibility = false
        }
    }

    fun setSatelliteEnabled(enabled: Boolean) {
        satelliteVisibility = enabled && satelliteMap != null && !satelliteLoadFailed
        satelliteLayer?.setProperties(visibility(if (satelliteEnabled) "visible" else "none"))
        map?.triggerRepaint()
    }

    fun fitSatelliteSample() {
        val satellite = satelliteMap ?: return
        fitBounds(satellite.bounds)
    }

    private fun fitInitialRegion() {
        if (needsInitialRegionFit && desired.route == null && !wasNavigating) {
            if (satelliteEnabled) fitSatelliteSample() else fitRegion()
        }
    }

    fun fitRegion() = fitBounds(visualBounds)

    private fun fitBounds(target: LatLngBounds) {
        view.post {
            if (!destroyed && style != null && view.width > 0 && view.height > 0) {
                if (wasNavigating) following = false
                needsInitialRegionFit = false
                val padding = (24 * view.resources.displayMetrics.density).toInt()
                map?.moveCamera(CameraUpdateFactory.newLatLngBounds(target, padding))
                map?.triggerRepaint()
            }
        }
    }

    private fun followPosition() {
        val location = userLocation ?: return
        val bearing = location.courseOverGround?.degrees?.toDouble() ?: map?.cameraPosition?.bearing ?: 0.0
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(location.coordinates.lat, location.coordinates.lng)).zoom(16.8)
            .bearing(bearing).tilt(25.0).build()))
    }

    private fun navigationArrow(): Bitmap {
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val path = Path().apply { moveTo(32f, 4f); lineTo(57f, 57f); lineTo(32f, 45f); lineTo(7f, 57f); close() }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f; strokeJoin = Paint.Join.ROUND }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL; paint.color = android.graphics.Color.rgb(28, 102, 224)
        canvas.drawPath(path, paint)
        return image
    }

    fun fitRoute() {
        if (wasNavigating) following = false
        val points = desired.route?.geometry?.takeIf { it.size >= 2 }
            ?: listOfNotNull(desired.origin, desired.destination)
        if (points.size >= 2 && points.distinct().size >= 2) {
            val routeBounds = LatLngBounds.Builder().includes(points.map { LatLng(it.latitude, it.longitude) }).build()
            view.post {
                if (!destroyed && view.width > 0 && view.height > 0) {
                    val padding = (32 * view.resources.displayMetrics.density).toInt()
                    map?.moveCamera(CameraUpdateFactory.newLatLngBounds(routeBounds, 0.0, 0.0, padding))
                    map?.triggerRepaint()
                }
            }
        }
    }

    fun destroy() {
        destroyed = true
        onPointSelected = {}
        onSettlementSelected = {}
        map = null
        style = null
        routeSource = null
        satelliteLayer = null
        view.onDestroy()
    }

    fun saveState(outState: Bundle) {
        outState.putBoolean(SATELLITE_STATE_KEY, satelliteEnabled)
        view.onSaveInstanceState(outState)
    }

    private fun emptyFeatures() = FeatureCollection.fromFeatures(emptyList<Feature>())
    private data class MapData(val origin: Coordinates?, val destination: Coordinates?, val route: RouteResult?)
    private companion object {
        const val SATELLITE_STATE_KEY = "batalhao.satellite.enabled"
    }
}

package br.com.palmasoffline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.geojson.Point

/** Uses the app's real controls, local image source and OpenGL snapshots. */
@RunWith(AndroidJUnit4::class)
class SatelliteRenderingDeviceTest {
    @Test fun cbersSampleHasLocalPixelsGeographicBoundsAndProvenance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metadata = sampleMetadata()
        assertTrue("A amostra precisa identificar o sensor", metadata.getString("title").contains("CBERS", true))
        assertTrue("A data da aquisição precisa acompanhar a imagem",
            metadata.getString("acquiredAt").startsWith("2026-08-04"))
        assertTrue(metadata.getString("attribution").isNotBlank())
        assertTrue(metadata.getString("licenseUrl").startsWith("https://"))
        assertTrue(metadata.getString("sourceUrl").startsWith("https://"))
        val bounds = metadata.getJSONArray("bounds")
        assertEquals(4, bounds.length())
        val west = bounds.getDouble(0)
        val south = bounds.getDouble(1)
        val east = bounds.getDouble(2)
        val north = bounds.getDouble(3)
        assertTrue(listOf(west, south, east, north).all { it.isFinite() })
        assertTrue(west < east && south < north)
        assertTrue("A amostra não cobre o Onalício Barros",
            -49.9269444 in west..east && -9.5666667 in south..north)
        assertTrue(BatalhaoRegion.contains(Coordinates(south, west)))
        assertTrue(BatalhaoRegion.contains(Coordinates(north, east)))

        val assetPath = imageAssetPath(metadata)
        val imageBytes = context.assets.open(assetPath).use { it.readBytes() }
        assertEquals("Imagem incompleta", metadata.getLong("imageBytes"), imageBytes.size.toLong())
        val hash = MessageDigest.getInstance("SHA-256").digest(imageBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("A imagem não corresponde à proveniência registrada", metadata.getString("imageSha256"), hash)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        assertEquals("image/png", options.outMimeType)
        assertEquals(metadata.getInt("width"), options.outWidth)
        assertEquals(metadata.getInt("height"), options.outHeight)
        assertTrue("Amostra sem resolução suficiente para o teste", options.outWidth >= 1024 && options.outHeight >= 1024)
    }

    @Test fun satellitePixelsRenderAndTogglingPreservesTheRouteAndDestination() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.filesDir, "satellite-test").apply { mkdirs() }
        val mapReady = CountDownLatch(1)
        lateinit var view: MapView
        lateinit var map: MapLibreMap
        lateinit var model: RouteViewModel
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                view = findMap(activity.window.decorView) ?: error("MapView ausente")
                model = ViewModelProvider(activity)[RouteViewModel::class.java]
                view.getMapAsync { ready ->
                    map = ready
                    ready.getStyle { mapReady.countDown() }
                }
            }
            assertTrue("O estilo local não carregou", mapReady.await(60, TimeUnit.SECONDS))
            scenario.onActivity {
                val style = requireNotNull(map.style)
                assertTrue("A imagem precisa ser uma fonte local do renderer",
                    style.getSource(SOURCE_ID) is ImageSource || style.getSource(SOURCE_ID) is RasterSource)
                assertTrue(style.getLayer(LAYER_ID) is RasterLayer)
                val layerIds = style.layers.map { layer -> layer.id }
                val rasterIndex = layerIds.indexOf(LAYER_ID)
                assertTrue("Camada raster ausente", rasterIndex >= 0)
                for (overlay in listOf("streams", "municipality-boundaries", "settlement-area-fill",
                    "settlement-area-outline", "roads", "access-roads", "settlement-points",
                    "route-line", "origin-circle", "destination-circle")) {
                    assertTrue("O fundo cobre a camada $overlay", layerIds.indexOf(overlay) > rasterIndex)
                }
            }
            clickControl(satelliteControl())
            clickControl(coverageControl())
            // The regional control fits eleven municipalities. Select the destination
            // at a useful detail level so neighbouring markers do not share the tap.
            if (OfflineSatellitePackage.load(instrumentation.targetContext) != null) {
                scenario.onActivity {
                    map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(-9.5666667, -49.9269444), 13.0))
                }
            }
            awaitFrame(scenario, view, map, "O ponto44 não foi renderizado na amostra") {
                map.queryRenderedFeatures(viewport(view), "settlement-points").any {
                    it.hasProperty("id") && it.getNumberProperty("id").toInt() == 44
                }
            }
            scenario.onActivity {
                val point = map.projection.toScreenLocation(
                    org.maplibre.android.geometry.LatLng(-9.5666667, -49.9269444))
                tapMap(view, point.x, point.y)
            }
            awaitFrame(scenario, view, map, "Selecionar o assentamento não atualizou o destino") {
                map.queryRenderedFeatures(viewport(view), "destination-circle").any {
                    val point = it.geometry() as? Point
                    point != null && kotlin.math.abs(point.latitude() + 9.5666667) < 0.00001 &&
                        kotlin.math.abs(point.longitude() + 49.9269444) < 0.00001
                }
            }
            scenario.onActivity {
                model.calculate("-9.273159", "-49.9576837", "-9.5666667", "-49.9269444")
            }
            val calculated = runBlocking {
                withTimeout(30_000) { model.state.first { !it.busy } }
            }
            assertNull("A rota local falhou", calculated.error)
            assertNotNull("A rota local não foi calculada", calculated.result)
            val canonicalRoute = requireNotNull(calculated.result)
            val canonicalGeometry = canonicalRoute.geometry.toList()
            // Let the route's automatic fit finish before exercising the coverage control.
            awaitFrame(scenario, view, map, "A rota não apareceu") {
                renderedGeometry(map, view, "route-line").isNotEmpty()
            }
            clickControl(coverageControl())
            awaitFrame(scenario, view, map, "Rota/destino ausentes na área da amostra") {
                renderedGeometry(map, view, "route-line").isNotEmpty() &&
                    onalicioDestinationIsRendered(map, view)
            }
            awaitIdle(scenario, view, map)
            lateinit var fixedCamera: CameraPosition
            scenario.onActivity {
                fixedCamera = map.cameraPosition
                assertTrue(renderedGeometry(map, view, "route-line").isNotEmpty())
                assertTrue(onalicioDestinationIsRendered(map, view))
                assertEquals(Property.VISIBLE, map.style!!.getLayer(LAYER_ID)!!.visibility.value)
            }
            val satellite = snapshot(scenario, map)
            saveBitmap(output, "satellite-enabled.png", satellite)

            clickControl("Vetorial")
            awaitFrame(scenario, view, map, "A camada de imagem não foi ocultada") {
                map.style!!.getLayer(LAYER_ID)!!.visibility.value == Property.NONE &&
                    renderedGeometry(map, view, "route-line").isNotEmpty() &&
                    onalicioDestinationIsRendered(map, view)
            }
            awaitIdle(scenario, view, map)
            val vector = snapshot(scenario, map)
            saveBitmap(output, "vector-only.png", vector)
            scenario.onActivity {
                assertSameCamera(fixedCamera, map.cameraPosition)
                assertTrue("Alternar o fundo ocultou a rota",
                    renderedGeometry(map, view, "route-line").isNotEmpty())
                assertTrue("Alternar o fundo alterou/ocultou o destino", onalicioDestinationIsRendered(map, view))
            }
            assertSame("Alternar o fundo recalculou ou perdeu a rota", canonicalRoute, model.state.value.result)
            assertEquals("Alternar o fundo alterou a geometria canônica", canonicalGeometry,
                model.state.value.result!!.geometry)

            clickControl(satelliteControl())
            awaitFrame(scenario, view, map, "A camada de imagem não voltou") {
                map.style!!.getLayer(LAYER_ID)!!.visibility.value == Property.VISIBLE &&
                    renderedGeometry(map, view, "route-line").isNotEmpty() &&
                    onalicioDestinationIsRendered(map, view)
            }
            awaitIdle(scenario, view, map)
            val restored = snapshot(scenario, map)
            saveBitmap(output, "satellite-restored.png", restored)
            scenario.onActivity {
                assertSameCamera(fixedCamera, map.cameraPosition)
                assertTrue("Reativar o satélite ocultou a rota", renderedGeometry(map, view, "route-line").isNotEmpty())
                assertTrue("Reativar o satélite alterou/ocultou o destino", onalicioDestinationIsRendered(map, view))
            }
            assertSame("Reativar o satélite recalculou ou perdeu a rota", canonicalRoute, model.state.value.result)
            assertEquals("Reativar o satélite alterou a geometria canônica", canonicalGeometry,
                model.state.value.result!!.geometry)
            val difference = pixelDifference(satellite, vector)
            val restoredDifference = pixelDifference(satellite, restored)
            val imagery: SatelliteMapInfo = OfflineSatellitePackage.load(instrumentation.targetContext)
                ?: requireNotNull(SatelliteSample.load(instrumentation.targetContext))
            val report = JSONObject()
                .put("map", imagery.title)
                .put("imageryDates", imagery.displayDate)
                .put("regional", imagery.regional)
                .put("changedPixelFraction", difference.changedFraction)
                .put("meanChannelDifference", difference.meanChannelDifference)
                .put("restoredChangedPixelFraction", restoredDifference.changedFraction)
                .put("restoredMeanChannelDifference", restoredDifference.meanChannelDifference)
                .put("width", satellite.width).put("height", satellite.height)
                .put("camera", fixedCamera.toString())
            scenario.onActivity {
                report.put("destinationPixelError", destinationPixelError(map, view))
                report.put("renderedDestination", org.json.JSONArray(
                    map.queryRenderedFeatures(viewport(view), "destination-circle")
                        .mapNotNull { it.geometry()?.toJson() }))
            }
            File(output, "comparison.json").writeText(report.toString(2))
            Log.i("SatelliteRenderingTest", "Capturas/medidas: ${output.absolutePath}; $report")
            assertTrue("Ativar a camada não acrescentou uma quantidade substancial de pixels: $report",
                difference.changedFraction > 0.15 && difference.meanChannelDifference > 8.0)
            assertTrue("Reativar o satélite não restaurou a mesma imagem: $report",
                restoredDifference.meanChannelDifference < 2.0)
        }
    }

    private fun sampleMetadata(): JSONObject =
        InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("batalhao/satellite/sample.json").bufferedReader().use { JSONObject(it.readText()) }

    private fun satelliteControl() = if (OfflineSatellitePackage.load(
        InstrumentationRegistry.getInstrumentation().targetContext) != null) "Satélite" else "Satélite (amostra)"

    private fun coverageControl() = if (OfflineSatellitePackage.load(
        InstrumentationRegistry.getInstrumentation().targetContext) != null) "Área do 8º BPM" else "Ver amostra"

    private fun imageAssetPath(metadata: JSONObject): String {
        val image = metadata.getString("image").removePrefix("asset://")
        assertFalse("A imagem do pacote precisa ser local", image.contains("://"))
        assertFalse("Caminho de imagem inválido", image.contains("..") || image.startsWith("/"))
        return if (image.startsWith("batalhao/")) image else "batalhao/satellite/$image"
    }

    private fun clickControl(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 5_000
        var visibleTexts = emptySet<String>()
        var diagnostic = "Janela do aplicativo indisponível"
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                // Compose exposes virtual children but may not implement the
                // AccessibilityNodeProvider's text-search operation.
                val pending = ArrayDeque<AccessibilityNodeInfo>()
                val observedTexts = linkedSetOf<String>()
                pending.addLast(root)
                var matched = false
                while (pending.isNotEmpty()) {
                    val node = pending.removeFirst()
                    val nodeText = node.text?.toString()
                    if (node.isVisibleToUser && !nodeText.isNullOrBlank()) observedTexts.add(nodeText)
                    if (node.isVisibleToUser && nodeText == text) {
                        matched = true
                        var clickable: AccessibilityNodeInfo? = node
                        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                        if (clickable?.isEnabled == true &&
                            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            instrumentation.waitForIdleSync()
                            return
                        }
                    }
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let(pending::addLast)
                    }
                }
                visibleTexts = observedTexts
                diagnostic = if (matched) "Rótulo encontrado, mas o controle não respondeu" else "Rótulo não encontrado"
            }
            SystemClock.sleep(minOf(100, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        fail("$diagnostic em até 5 segundos: $text. Textos visíveis: ${visibleTexts.take(40).joinToString(" | ")}")
    }

    private fun awaitFrame(
        scenario: ActivityScenario<MainActivity>,
        view: MapView,
        map: MapLibreMap,
        failure: String,
        condition: () -> Boolean,
    ) {
        val rendered = CountDownLatch(1)
        scenario.onActivity {
            view.addOnDidFinishRenderingFrameListener { fullyRendered, _, _ ->
                if (fullyRendered && view.width > 0 && view.height > 0 &&
                    map.style?.isFullyLoaded == true && condition()) rendered.countDown()
            }
            map.triggerRepaint()
        }
        if (!rendered.await(30, TimeUnit.SECONDS)) {
            val diagnostic = AtomicReference<String>()
            scenario.onActivity {
                diagnostic.set(JSONObject().put("camera", map.cameraPosition.toString())
                    .put("styleLoaded", map.style?.isFullyLoaded)
                    .put("routeFeatures", renderedGeometry(map,view,"route-line").size)
                    .put("destinationPixelError", destinationPixelError(map,view))
                    .put("destinationFeatures", org.json.JSONArray(map.queryRenderedFeatures(viewport(view),"destination-circle")
                        .mapNotNull { it.geometry()?.toJson() })).toString(2))
            }
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"satellite-test").apply { mkdirs() }
            File(directory,"frame-timeout.json").writeText(diagnostic.get())
            saveBitmap(directory,"frame-timeout.png",snapshot(scenario,map))
            fail("$failure: ${diagnostic.get()}")
        }
    }

    private fun snapshot(scenario: ActivityScenario<MainActivity>, map: MapLibreMap): Bitmap {
        val image = AtomicReference<Bitmap?>()
        val completed = CountDownLatch(1)
        scenario.onActivity {
            map.snapshot { bitmap ->
                image.set(bitmap)
                completed.countDown()
            }
        }
        assertTrue("O renderer não forneceu a captura", completed.await(15, TimeUnit.SECONDS))
        return requireNotNull(image.get()) { "Captura nula do renderer" }
    }

    private fun awaitIdle(scenario: ActivityScenario<MainActivity>, view: MapView, map: MapLibreMap) {
        val idle = CountDownLatch(1)
        scenario.onActivity {
            // A complete frame may still contain tiles from the previous camera.
            // Idle is emitted after tile requests and transitions have settled.
            view.addOnDidBecomeIdleListener { idle.countDown() }
            map.triggerRepaint()
        }
        if (idle.await(700, TimeUnit.MILLISECONDS)) return
        // No new idle event is required when cached tiles were already settled.
        val deadline = SystemClock.uptimeMillis() + 15_000
        var previous = snapshot(scenario, map)
        var stable = 0
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(120)
            val current = snapshot(scenario, map)
            if (pixelDifference(previous, current).meanChannelDifference < 0.01) stable++ else stable = 0
            previous.recycle()
            previous = current
            if (stable >= 3) { previous.recycle(); return }
        }
        previous.recycle()
        fail("O renderer não estabilizou antes da captura")
    }

    private fun saveBitmap(directory: File, name: String, bitmap: Bitmap) {
        File(directory, name).outputStream().use {
            assertTrue("Não foi possível salvar a captura $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun renderedGeometry(map: MapLibreMap, view: MapView, layer: String): Set<String> =
        map.queryRenderedFeatures(viewport(view), layer).mapNotNull { it.geometry()?.toJson() }.toSet()

    private fun onalicioDestinationIsRendered(map: MapLibreMap, view: MapView): Boolean =
        destinationPixelError(map,view)?.let { it <= 1.0 } == true

    private fun destinationPixelError(map: MapLibreMap, view: MapView): Double? {
        // Rendered GeoJSON features have tile quantization. At a regional zoom
        // a fixed metre tolerance rejects a correctly positioned marker.
        val expected = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(-9.5666667,-49.9269444))
        return map.queryRenderedFeatures(viewport(view),"destination-circle").mapNotNull {
            val point = it.geometry() as? Point ?: return@mapNotNull null
            val actual = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(point.latitude(),point.longitude()))
            kotlin.math.hypot((actual.x-expected.x).toDouble(),(actual.y-expected.y).toDouble())
        }.minOrNull()
    }

    private fun viewport(view: MapView) = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())

    private fun assertSameCamera(expected: CameraPosition, actual: CameraPosition) {
        assertEquals(expected.target!!.latitude, actual.target!!.latitude, 0.000000001)
        assertEquals(expected.target!!.longitude, actual.target!!.longitude, 0.000000001)
        assertEquals(expected.zoom, actual.zoom, 0.000000001)
        assertEquals(expected.bearing, actual.bearing, 0.000000001)
        assertEquals(expected.tilt, actual.tilt, 0.000000001)
    }

    private data class PixelDifference(val changedFraction: Double, val meanChannelDifference: Double)

    private fun pixelDifference(first: Bitmap, second: Bitmap): PixelDifference {
        assertEquals("O tamanho do mapa mudou ao alternar o fundo", first.width, second.width)
        assertEquals("O tamanho do mapa mudou ao alternar o fundo", first.height, second.height)
        val count = first.width * first.height
        val before = IntArray(count)
        val after = IntArray(count)
        first.getPixels(before, 0, first.width, 0, 0, first.width, first.height)
        second.getPixels(after, 0, second.width, 0, 0, second.width, second.height)
        var total = 0L
        var changed = 0
        for (index in 0 until count) {
            val red = kotlin.math.abs(Color.red(before[index]) - Color.red(after[index]))
            val green = kotlin.math.abs(Color.green(before[index]) - Color.green(after[index]))
            val blue = kotlin.math.abs(Color.blue(before[index]) - Color.blue(after[index]))
            val difference = red + green + blue
            total += difference
            if (difference > 45) changed++
        }
        return PixelDifference(changed.toDouble() / count, total.toDouble() / (count * 3))
    }

    private fun tapMap(view: MapView, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    companion object {
        private const val SOURCE_ID = "satellite-sample"
        private const val LAYER_ID = "satellite-sample-raster"
    }
}

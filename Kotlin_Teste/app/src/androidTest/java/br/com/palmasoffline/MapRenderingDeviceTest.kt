package br.com.palmasoffline

import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.geojson.Point
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MapRenderingDeviceTest {
    @Test fun nativeRendererDrawsSettlementPerimetersAndSelectsAReferenceAsDestination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val areas = context.assets.open("batalhao/settlement_areas.geojson").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("features")
        }
        assertTrue("Nenhum perímetro de assentamento foi empacotado", areas.length() > 0)
        val area = areas.getJSONObject(0)
        val areaName = area.getJSONObject("properties").getString("nome")
        val positions = mutableListOf<LatLng>()
        collectPositions(area.getJSONObject("geometry").getJSONArray("coordinates"), positions)
        val areaBounds = LatLngBounds.Builder().includes(positions).build()
        val pointDrawn = CountDownLatch(1)
        val destinationDrawn = CountDownLatch(1)
        val perimeterDrawn = CountDownLatch(1)
        var inspectingDestination = false
        var inspectingPerimeter = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val view = findMap(activity.window.decorView) ?: error("MapView ausente na interface")
                view.getMapAsync { map ->
                    fun checkFeatures() {
                        if (view.width == 0 || view.height == 0 || map.style?.isFullyLoaded != true) return
                        val viewport = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
                        if (!inspectingPerimeter && map.cameraPosition.zoom > 14.5) {
                            val points = map.queryRenderedFeatures(viewport, "settlement-points")
                            if (points.any { it.hasProperty("id") && it.getNumberProperty("id").toInt() == 44 }) {
                                pointDrawn.countDown()
                            }
                        }
                        if (inspectingDestination) {
                            val destinations = map.queryRenderedFeatures(viewport, "destination-circle")
                            if (destinations.any {
                                val point = it.geometry() as? Point
                                point != null && kotlin.math.abs(point.latitude() + 9.5666667) < 0.00001 &&
                                    kotlin.math.abs(point.longitude() + 49.9269444) < 0.00001
                            }) destinationDrawn.countDown()
                        }
                        if (inspectingPerimeter) {
                            val fills = map.queryRenderedFeatures(viewport, "settlement-area-fill")
                            val outlines = map.queryRenderedFeatures(viewport, "settlement-area-outline")
                            if (fills.any { it.getStringProperty("nome") == areaName } &&
                                outlines.any { it.getStringProperty("nome") == areaName }) {
                                perimeterDrawn.countDown()
                            }
                        }
                    }
                    view.addOnDidBecomeIdleListener { checkFeatures() }
                    view.addOnDidFinishRenderingFrameListener { _, _, _ -> checkFeatures() }
                    map.getStyle {
                        view.post {
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-9.5666667, -49.9269444), 15.0))
                            map.triggerRepaint()
                        }
                    }
                }
            }
            assertTrue("O renderer não desenhou o ponto do Onalício Barros",
                pointDrawn.await(60, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val view = findMap(activity.window.decorView) ?: error("MapView ausente na interface")
                view.getMapAsync { map ->
                    inspectingDestination = true
                    val pixel = map.projection.toScreenLocation(LatLng(-9.5666667, -49.9269444))
                    val downTime = SystemClock.uptimeMillis()
                    // Exercise the actual map gesture and the Compose callback without
                    // screen coordinates, external input tools or a manual tap.
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, pixel.x, pixel.y, 0)
                    val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, pixel.x, pixel.y, 0)
                    try {
                        view.dispatchTouchEvent(down)
                        view.dispatchTouchEvent(up)
                    } finally {
                        down.recycle()
                        up.recycle()
                    }
                }
            }
            assertTrue("Tocar no assentamento não atualizou o marcador B para a referência selecionada",
                destinationDrawn.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val view = findMap(activity.window.decorView) ?: error("MapView ausente na interface")
                view.getMapAsync { map ->
                    inspectingPerimeter = true
                    val padding = (24 * view.resources.displayMetrics.density).toInt()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(areaBounds, padding))
                    map.triggerRepaint()
                }
            }
            assertTrue("O renderer não desenhou o preenchimento e o contorno do perímetro $areaName",
                perimeterDrawn.await(30, TimeUnit.SECONDS))
        }
    }

    @Test fun nativeRendererDrawsRoadsAndFitsCalculatedRoute() {
        val drawn = CountDownLatch(1)
        val routeDrawn = CountDownLatch(1)
        lateinit var routeModel: RouteViewModel
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val view = findMap(activity.window.decorView)
                    ?: error("MapView ausente na interface")
                view.getMapAsync { map ->
                    fun checkRoads() {
                        if (view.width > 0 && view.height > 0 && map.style?.isFullyLoaded == true) {
                            val features = map.queryRenderedFeatures(
                                RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "roads")
                            if (features.isNotEmpty()) drawn.countDown()
                            val routes = map.queryRenderedFeatures(
                                RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "route-line")
                            if (routes.isNotEmpty() && map.cameraPosition.zoom > 15.0) routeDrawn.countDown()
                        }
                    }
                    view.addOnDidBecomeIdleListener { checkRoads() }
                    view.addOnDidFinishRenderingFrameListener { _, _, _ -> checkRoads() }
                    map.getStyle { checkRoads() }
                    checkRoads()
                }
            }
            assertTrue("Nenhuma rua local foi desenhada pelo renderer", drawn.await(60, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                routeModel = ViewModelProvider(activity)[RouteViewModel::class.java]
                routeModel.calculate(
                    "-10.174828510086803", "-48.88691423323496", "-10.173246456726565", "-48.887796726552125")
            }
            // Cold loading of the native actor is independent of renderer responsiveness.
            val calculated = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(20_000) { routeModel.state.first { !it.busy } }
            }
            org.junit.Assert.assertNull("Falha no cálculo local", calculated.error)
            val didDraw = routeDrawn.await(5, TimeUnit.SECONDS)
            var diagnostics = ""
            scenario.onActivity { activity ->
                findMap(activity.window.decorView)?.getMapAsync { map ->
                    diagnostics = "zoom=${map.cameraPosition.zoom}; mapa=${findMap(activity.window.decorView)?.height} px"
                }
            }
            assertTrue("A rota calculada não apareceu em até 5 segundos sem outra interação ($diagnostics)", didDraw)
        }
    }

    private fun collectPositions(coordinates: JSONArray, destination: MutableList<LatLng>) {
        if (coordinates.get(0) is Number) {
            destination += LatLng(coordinates.getDouble(1), coordinates.getDouble(0))
        } else {
            for (index in 0 until coordinates.length()) {
                collectPositions(coordinates.getJSONArray(index), destination)
            }
        }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}

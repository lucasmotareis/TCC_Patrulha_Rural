package br.com.palmasoffline

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import org.maplibre.android.maps.MapView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MapRenderingDeviceTest {
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
                    "-10.247473", "-48.341647", "-10.246252", "-48.338953")
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

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}

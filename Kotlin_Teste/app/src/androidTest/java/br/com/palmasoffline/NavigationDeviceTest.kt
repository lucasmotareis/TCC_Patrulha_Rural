package br.com.palmasoffline

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.HttpClientProvider
import com.stadiamaps.ferrostar.core.http.IResponse
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ferrostar.*

/** Uses the actual published Ferrostar native core and the Valhalla tiles packaged in this APK. */
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    private val origin = Coordinates(-10.247473, -48.341647)
    private val destination = Coordinates(-10.246252, -48.338953)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeOsrmConversionPreservesGeometryUnitsAndGuidance() {
        OfflineRouter(context).use { router ->
            val native = router.route(origin, destination)
            val raw = router.navigationJson(origin, destination)
            val route = OfflineNavigationRouteProvider.decode(raw).first()
            assertTrue(route.distance in 100.0..3000.0)
            assertEquals(native.kilometers * 1000, route.distance, 3.0)
            assertEquals(native.geometry.first().latitude, route.geometry.first().lat, 0.000002)
            assertEquals(native.geometry.last().longitude, route.geometry.last().lng, 0.000002)
            assertTrue(route.steps.all { it.geometry.isNotEmpty() })
            assertTrue("Missing visual guidance", route.steps.any { it.visualInstructions.isNotEmpty() })
            assertTrue("Missing spoken guidance", route.steps.any { it.spokenInstructions.isNotEmpty() })
            assertTrue(native.maneuvers.all { it.beginShapeIndex in native.geometry.indices && it.endShapeIndex in it.beginShapeIndex until native.geometry.size })
            val response = JSONObject(raw)
            val legs = response.getJSONArray("routes").getJSONObject(0).getJSONArray("legs")
            assertTrue(legs.getJSONObject(0).getJSONArray("steps").length() > 1)
            val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertFalse("Offline app must not request INTERNET", permissions.contains(Manifest.permission.INTERNET))
        }
    }

    @Test fun malformedRouteFailsWithoutRemoteFallback() {
        assertThrows(Exception::class.java) { OfflineNavigationRouteProvider.decode("{\"code\":\"NoRoute\"}") }
        assertThrows(Exception::class.java) { OfflineNavigationRouteProvider.decode("{\"code\":\"Ok\",\"routes\":[],\"waypoints\":[]}") }
    }

    @Test fun simulatorAdvancesRealFerrostarCoreToArrival() = runBlocking {
        OfflineRouter(context).use { router ->
            val route = OfflineNavigationRouteProvider.decode(router.navigationJson(origin, destination)).first()
            val simulationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val simulated = SimulatedLocationProvider(simulationScope, warpFactor = 20u, initialLocation = RouteViewModel.simulatedLocation(origin))
            simulated.setRoute(route)
            val noNetwork = object : HttpClientProvider { override suspend fun call(request: RouteRequest): IResponse = error("Unexpected network request") }
            val core = FerrostarCore(OfflineNavigationRouteProvider(router), noNetwork, simulated, RouteViewModel.navigationConfig())
            try {
                core.startNavigation(route)
                assertTrue(core.state.value.tripState is TripState.Navigating)
                withTimeout(30_000) { core.state.first { it.tripState is TripState.Complete } }
            } finally { core.stopNavigation(); simulationScope.cancel() }
            assertTrue(core.state.value.tripState is TripState.Idle)
        }
    }

    @Test fun multiLegIndexesAreAdjustedAfterSharedEndpointRemoval() {
        OfflineRouter(context).use { router ->
            val route = router.route(origin, destination)
            // Use an actual encoded leg twice to verify the parsing offset, independent of a multi-stop UI.
            val encoded = PolylineFixture.encode(route.geometry)
            val one = JSONObject().put("shape", encoded).put("maneuvers", org.json.JSONArray().put(JSONObject()
                .put("instruction", "Siga").put("length", 1.0).put("time", 10.0).put("begin_shape_index", 0).put("end_shape_index", route.geometry.lastIndex).put("type", 1)))
            val reverse = route.geometry.asReversed()
            val two = JSONObject(one.toString()).put("shape", PolylineFixture.encode(reverse))
            val parsed = OfflineRouter.parseTrip(JSONObject().put("summary", JSONObject().put("length", 2).put("time", 20))
                .put("legs", org.json.JSONArray().put(one).put(two)))
            assertEquals(route.geometry.size * 2 - 1, parsed.geometry.size)
            assertEquals(route.geometry.lastIndex, parsed.maneuvers[1].beginShapeIndex)
            assertEquals(parsed.geometry.lastIndex, parsed.maneuvers[1].endShapeIndex)
        }
    }

    @Test fun activitySimulationStopsCleanlyAndLateCalculationCannotRestoreIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: RouteViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RouteViewModel::class.java]; model.prepareSimulation(origin, destination) }
            withTimeout(20_000) { model.session.first { it.phase == NavigationPhase.PREVIEW } }
            assertNull(model.state.value.error)
            scenario.onActivity { model.beginNavigation() }
            withTimeout(10_000) { model.navigationUiState.first { it.progress != null } }
            scenario.onActivity { model.stopNavigation() }
            delay(1000)
            assertEquals(NavigationPhase.PLANNING, model.session.value.phase)
            assertNull(model.navigationUiState.value.progress)
            scenario.onActivity { model.prepareSimulation(origin, destination); model.stopNavigation() }
            delay(1500)
            assertEquals(NavigationPhase.PLANNING, model.session.value.phase)
            assertFalse(model.state.value.busy)
            assertNull(model.navigationUiState.value.progress)
        }
    }
}

private object PolylineFixture {
    fun encode(points: List<Coordinates>): String {
        val result = StringBuilder()
        var latitude = 0L; var longitude = 0L
        fun value(delta: Long) {
            var n = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (n >= 0x20) { result.append(((n and 0x1f) or 0x20).toInt().plus(63).toChar()); n = n shr 5 }
            result.append((n + 63).toInt().toChar())
        }
        for (point in points) {
            val lat = kotlin.math.round(point.latitude * 1e6).toLong()
            val lon = kotlin.math.round(point.longitude * 1e6).toLong()
            value(lat - latitude); value(lon - longitude)
            latitude = lat; longitude = lon
        }
        return result.toString()
    }
}

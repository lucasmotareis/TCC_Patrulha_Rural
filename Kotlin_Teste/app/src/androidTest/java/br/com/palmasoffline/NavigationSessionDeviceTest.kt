package br.com.palmasoffline

import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationSessionDeviceTest {
    @Test fun stoppingDuringUninterruptibleRerouteDiscardsItsResult() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as android.app.Application
        val origin = Coordinates(-10.247473, -48.341647)
        val destination = Coordinates(-10.246252, -48.338953)
        val route = OfflineRouter(application).use { OfflineNavigationRouteProvider.decode(it.navigationJson(origin, destination)).first() }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val provider = com.stadiamaps.ferrostar.core.CustomRouteProvider { _, _ ->
            if (requests.incrementAndGet() == 2) {
                // Models a native call that keeps running even after its coroutine is cancelled.
                withContext(NonCancellable) { started.complete(Unit); release.await() }
                returned.complete(Unit)
            }
            listOf(route)
        }
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var model: RouteViewModel
        instrumentation.runOnMainSync {
            model = RouteViewModel(application, provider)
            store.put("navigation-test", model)
            model.onForeground()
            model.prepareSimulation(origin, destination)
        }
        try {
            withTimeout(10_000) { model.session.first { it.phase == NavigationPhase.PREVIEW } }
            instrumentation.runOnMainSync { model.beginNavigation() }
            withTimeout(5000) { model.navigationUiState.first { it.progress != null } }
            instrumentation.runOnMainSync { model.simulateDeviation() }
            withTimeout(15_000) { started.await() }
            assertEquals(NavigationPhase.RECALCULATING, model.session.value.phase)
            instrumentation.runOnMainSync { model.stopNavigation() }
            release.complete(Unit)
            withTimeout(5000) { returned.await() }
            delay(500)
            assertEquals(NavigationPhase.PLANNING, model.session.value.phase)
            assertFalse(model.state.value.busy)
            assertNull(model.navigationUiState.value.progress)
            assertNull(model.state.value.error)
        } finally {
            release.complete(Unit)
            instrumentation.runOnMainSync { model.onBackground(); model.stopNavigation(); store.clear() }
        }
    }

    @Test fun simulatedDeviationRecalculatesLocallyAndBackgroundPausesSession() = runBlocking {
        val origin = Coordinates(-10.247473, -48.341647)
        val destination = Coordinates(-10.246252, -48.338953)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: RouteViewModel
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model = ViewModelProvider(activity)[RouteViewModel::class.java]
                model.prepareSimulation(origin, destination)
            }
            withTimeout(20_000) { model.session.first { it.phase == NavigationPhase.PREVIEW } }
            assertNull(model.state.value.error)
            scenario.onActivity { model.beginNavigation() }
            withTimeout(10_000) { model.navigationUiState.first { it.progress != null } }
            delay(1000)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if (screenshot != null) File(instrumentation.targetContext.getExternalFilesDir(null), "navigation-simulation.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            scenario.onActivity { model.simulateDeviation() }
            withTimeout(20_000) { model.session.first { it.recalculations >= 1 } }
            assertNull(model.state.value.error)
            assertTrue(model.session.value.simulated)
            assertTrue(model.state.value.result!!.geometry.size > 1)
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(NavigationPhase.PAUSED, model.session.value.phase)
            val stoppedProgress = model.navigationUiState.value
            delay(1000)
            assertEquals(stoppedProgress, model.navigationUiState.value)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { model.stopNavigation() }
            assertEquals(NavigationPhase.PLANNING, model.session.value.phase)
        }
    }
}

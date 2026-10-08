package br.com.palmasoffline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real native engine and the exact tiles packaged by this prototype. */
@RunWith(AndroidJUnit4::class)
class OfflineRouterDeviceTest {
    @Test fun routesBetweenThePalmasPointsUsingLocalTiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OfflineRouter(context).use { router ->
            val result = router.route(
                Coordinates(-10.247473, -48.341647),
                Coordinates(-10.246252, -48.338953))
            assertTrue("Expected a short route in Palmas", result.kilometers in 0.1..3.0)
            assertTrue(result.seconds > 0)
            assertTrue(result.maneuvers.isNotEmpty())
            assertTrue(result.maneuvers.all { it.instruction.isNotBlank() })
            assertTrue(result.geometry.size >= 2)
            assertEquals(-10.247473, result.geometry.first().latitude, 0.002)
            assertEquals(-48.338953, result.geometry.last().longitude, 0.002)
        }
    }

    @Test fun reportsAnErrorOutsideTheInstalledRegion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OfflineRouter(context).use { router ->
            assertThrows(IllegalArgumentException::class.java) {
                router.route(Coordinates(42.5063, 1.5218), Coordinates(42.5086, 1.5394))
            }
        }
    }
}

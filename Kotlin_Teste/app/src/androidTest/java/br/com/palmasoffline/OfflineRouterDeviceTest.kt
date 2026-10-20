package br.com.palmasoffline

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real native engine and the exact tiles packaged by this prototype. */
@RunWith(AndroidJUnit4::class)
class OfflineRouterDeviceTest {
    @Test fun newOnalicioAccessRoutesFromCasearaInBothDirections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val caseara = Coordinates(-9.273159, -49.9576837)
        val onalicio = Coordinates(-9.5666667, -49.9269444)
        val accessJunction = Coordinates(-9.572569, -49.924594)
        // Reference: dados_batalhao/onalicio_teste/validacao_onalicio.json.
        // The previous package could not find a road near this settlement.
        OfflineRouter(context).use { router ->
            for ((origin, destination) in listOf(caseara to onalicio, onalicio to caseara)) {
                val result = router.route(origin, destination)
                assertTrue("Distância inesperada no percurso Caseara–Onalício: ${result.kilometers} km",
                    result.kilometers in 40.5..41.2)
                assertTrue("O percurso não utilizou o acesso novo",
                    result.maneuvers.any { it.instruction.contains("Acesso ao Assentamento Onalício Barros") })
                assertTrue("A geometria não passa pela ligação do acesso à malha existente",
                    result.geometry.any { metersBetween(it, accessJunction) < 15f })
                assertTrue("O percurso começou longe do ponto solicitado",
                    metersBetween(result.geometry.first(), origin) < 40f)
                assertTrue("O percurso terminou longe do ponto solicitado",
                    metersBetween(result.geometry.last(), destination) < 40f)
            }
        }
    }

    @Test fun newChacaraAccessParticipatesInRouting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OfflineRouter(context).use { router ->
            val result = router.route(Coordinates(-10.1417377, -49.1781853),
                Coordinates(-10.119233731352583, -49.18961793050047))
            assertTrue(result.kilometers in 3.4..3.7)
            assertTrue(result.maneuvers.any { it.instruction.contains("Chácara 2 Meninos") })
            assertEquals(-10.119233731352583, result.geometry.last().latitude, 0.00002)
            assertEquals(-49.18961793050047, result.geometry.last().longitude, 0.00002)
        }
    }

    @Test fun sameSizeCorruptedArchiveIsReinstalled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val from = Coordinates(-10.174828510086803, -48.88691423323496)
        val to = Coordinates(-10.173246456726565, -48.887796726552125)
        OfflineRouter(context).use { it.route(from, to) }
        val metadata = context.assets.open("batalhao/metadata.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
        val hash = metadata.getString("routingSha256")
        val file = java.io.File(context.filesDir, "batalhao_${hash.take(16)}.tar")
        val length = file.length()
        java.io.RandomAccessFile(file, "rw").use { io ->
            val first = io.readByte().toInt()
            io.seek(0)
            io.writeByte(first xor 0xff)
        }
        assertEquals(length, file.length())
        OfflineRouter(context).use { assertTrue(it.route(from, to).kilometers > 0) }
    }

    @Test fun routesBetweenParaisoPointsUsingLocalTiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OfflineRouter(context).use { router ->
            val result = router.route(
                Coordinates(-10.174828510086803, -48.88691423323496),
                Coordinates(-10.173246456726565, -48.887796726552125))
            assertTrue("Expected a short route in Paraiso", result.kilometers in 0.1..3.0)
            assertTrue(result.seconds > 0)
            assertTrue(result.maneuvers.isNotEmpty())
            assertTrue(result.maneuvers.all { it.instruction.isNotBlank() })
            assertTrue(result.geometry.size >= 2)
            assertEquals(-10.174828510086803, result.geometry.first().latitude, 0.002)
            assertEquals(-48.887796726552125, result.geometry.last().longitude, 0.002)
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

    private fun metersBetween(first: Coordinates, second: Coordinates): Float {
        val result = FloatArray(1)
        Location.distanceBetween(first.latitude, first.longitude, second.latitude, second.longitude, result)
        return result[0]
    }
}

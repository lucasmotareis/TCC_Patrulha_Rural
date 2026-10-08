package br.com.palmasoffline

import android.Manifest
import android.location.LocationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the framework request itself; a fused/balanced regression would fail this test. */
@RunWith(AndroidJUnit4::class)
class GnssProviderDeviceTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    @Test fun activatesSatelliteProviderAtHighAccuracyAndReleasesItWhenCancelled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        assumeTrue("GPS precisa estar habilitado no aparelho de teste", manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val provider = GnssLocationProvider(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val updates = scope.launch { provider.locationUpdates().collect {} }
            fun gpsRequests(): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys location"))
                .bufferedReader().use { it.readText() }.substringAfter("    gps provider:").substringBefore("      last location=")
            try {
                withTimeout(20_000) { provider.reception.first { it.started } }
                val requests = gpsRequests()
                assertTrue("Aplicativo não solicitou o provedor GPS por satélite", requests.contains(context.packageName))
                if (Build.VERSION.SDK_INT >= 31) assertTrue("Pedido não é de alta precisão", requests.contains("HIGH_ACCURACY"))
                updates.cancelAndJoin()
                assertFalse("Pedido de GPS permaneceu depois de cancelar", gpsRequests().contains(context.packageName))
            } finally { updates.cancelAndJoin(); scope.cancel() }
        }
    }
}

package br.com.palmasoffline

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMapAssetsDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun mapSourcesAndGlyphsAreBundledAndInternetIsNotRequested() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val style = context.assets.open("palmas/style.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("asset://palmas/fonts/{fontstack}/{range}.pbf", style.getString("glyphs"))
        val sources = style.getJSONObject("sources")
        for (source in listOf("roads", "areas", "waterways", "places")) {
            val path = sources.getJSONObject(source).getString("data")
            assertTrue(path.startsWith("asset://palmas/"))
            context.assets.open(path.removePrefix("asset://")).use { assertTrue(it.read() >= 0) }
        }
        context.assets.open("palmas/fonts/Open Sans Semibold/0-255.pbf").use { assertTrue(it.read() >= 0) }
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
    }
}

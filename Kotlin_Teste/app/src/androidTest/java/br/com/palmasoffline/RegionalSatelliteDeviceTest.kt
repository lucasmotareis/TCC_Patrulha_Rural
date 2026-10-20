package br.com.palmasoffline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RegionalSatelliteDeviceTest {
    @Test fun nativeFileRasterTilesReadWithoutNetworkPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            instrumentation.targetContext.checkSelfPermission(android.Manifest.permission.INTERNET))
        val fixture = File(instrumentation.targetContext.cacheDir, "raster-fixture").apply { mkdirs() }
        fun copy(path: String) {
            val children = instrumentation.context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                val destination = File(fixture, path.removePrefix("raster-fixture/"))
                destination.parentFile!!.mkdirs()
                instrumentation.context.assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
            } else children.forEach { copy("$path/$it") }
        }
        copy("raster-fixture")
        val metadata = JSONObject(File(fixture, "metadata.json").readText())
        val values = metadata.getJSONArray("bounds")
        val tileSet = TileSet("2.2.0", Uri.fromFile(fixture).toString() + "/14/{x}/{y}.png")
        tileSet.minZoom = 14f
        tileSet.maxZoom = 14f
        tileSet.setBounds(LatLngBounds.from(values.getDouble(3),values.getDouble(2),values.getDouble(1),values.getDouble(0)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (view,map) = mapReady(scenario)
            scenario.onActivity {
                map.style!!.getLayer("satellite-sample-raster")?.setProperties(visibility("none"))
                map.style!!.addSource(RasterSource("file-fixture",tileSet,256))
                map.style!!.addLayerBelow(RasterLayer("file-fixture-raster","file-fixture").withProperties(visibility("none")),"streams")
                view.post { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-9.5666667,-49.9269444),15.0)) }
            }
            idle(scenario,view,map)
            val before = snapshot(scenario,map)
            scenario.onActivity { map.style!!.getLayer("file-fixture-raster")!!.setProperties(visibility("visible")) }
            // A queued idle event can precede the newly visible source's disk reads.
            // Wait for actual image pixels, retaining the same minimum difference.
            val deadline = SystemClock.uptimeMillis()+15_000
            var after = snapshot(scenario,map)
            while(difference(before,after)<=10.0 && SystemClock.uptimeMillis()<deadline) {
                after.recycle()
                SystemClock.sleep(100)
                after = snapshot(scenario,map)
            }
            val difference = difference(before,after)
            val directory = File(instrumentation.targetContext.filesDir,"regional-satellite-test").apply { mkdirs() }
            save(directory,"file-fixture.png",after)
            save(directory,"file-fixture-vector.png",before)
            File(directory,"file-fixture.json").writeText(JSONObject().put("meanDifference",difference).put("url",tileSet.tiles.first()).toString(2))
            assertTrue("O renderer não leu os blocos PNG locais: $difference",difference > 10.0)
        }
    }

    @Test fun regionalPackageChecksumsAndPixelDataCoverElevenMunicipalities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = requireNotNull(OfflineSatellitePackage.load(context)) { "Pacote regional não instalado/validado" }
        assertTrue(installed.tileCount > 1000)
        assertTrue(installed.tileTemplate.startsWith("file://"))
        assertFalse(installed.tileTemplate.contains("http"))
        val metadata = JSONObject(File(installed.directory,"package.json").readText())
        val probes = metadata.getJSONArray("probes")
        val municipalities = mutableSetOf<String>()
        for (index in 0 until probes.length()) {
            val probe = probes.getJSONObject(index)
            municipalities += probe.getString("code")
            val source = File(installed.directory,probe.getString("path"))
            assertEquals(probe.getLong("bytes"),source.length())
            val digest = source.inputStream().use { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) { val count=input.read(buffer); if(count<0) break; hash.update(buffer,0,count) }
                hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            assertEquals("Bloco incorreto: ${source.name}",probe.getString("sha256"),digest)
            val image = requireNotNull(BitmapFactory.decodeFile(source.absolutePath))
            assertEquals(256,image.width)
            assertEquals(256,image.height)
            assertTrue("Bloco sem pixels visíveis",(0 until 256 step 16).any { y ->
                (0 until 256 step 16).any { x -> Color.alpha(image.getPixel(x,y)) > 0 }
            })
            image.recycle()
        }
        assertEquals(11,municipalities.size)
        assertTrue(metadata.getJSONObject("coverage").getDouble("coveredFraction") > 0.999)
        assertEquals(0,metadata.getJSONObject("coverage").getInt("visibleOutsideRegionAfterMask"))
    }

    @Test fun regionalRasterRendersAcrossAllElevenMunicipalities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = requireNotNull(OfflineSatellitePackage.load(context))
        val metadata = JSONObject(File(installed.directory,"package.json").readText())
        val probes = metadata.getJSONArray("probes")
        val output = File(context.filesDir,"regional-satellite-test").apply { mkdirs() }
        val results = org.json.JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (view,map) = mapReady(scenario)
            scenario.onActivity { assertTrue(map.style!!.getSource("satellite-sample") is RasterSource) }
            for (index in 0 until probes.length()) {
                val probe = probes.getJSONObject(index)
                if (probe.getInt("zoom") != installed.maxZoom) continue
                val target = LatLng(probe.getDouble("latitude"),probe.getDouble("longitude"))
                scenario.onActivity { view.post { map.moveCamera(CameraUpdateFactory.newLatLngZoom(target,15.0)) } }
                idle(scenario,view,map)
                val image = snapshot(scenario,map)
                scenario.onActivity { map.style!!.getLayer("satellite-sample-raster")!!.setProperties(visibility("none")) }
                idle(scenario,view,map)
                val vector = snapshot(scenario,map)
                val measured = difference(image,vector)
                assertTrue("Imagem não renderizada em ${probe.getString("municipality")}: $measured",measured > 3.0)
                save(output,probe.getString("code")+".png",image)
                results.put(JSONObject().put("municipality",probe.getString("municipality")).put("meanDifference",measured))
                scenario.onActivity { map.style!!.getLayer("satellite-sample-raster")!!.setProperties(visibility("visible")) }
                image.recycle()
                vector.recycle()
            }
        }
        File(output,"municipalities.json").writeText(results.toString(2))
        assertEquals(11,results.length())
    }

    private fun mapReady(scenario: ActivityScenario<MainActivity>): Pair<MapView,MapLibreMap> {
        val ready=CountDownLatch(1)
        lateinit var view:MapView
        lateinit var map:MapLibreMap
        scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view=requireNotNull(findMap(activity.window.decorView))
            view.getMapAsync { map=it; it.getStyle { ready.countDown() } }
        }
        assertTrue(ready.await(45,TimeUnit.SECONDS))
        return view to map
    }

    private fun idle(scenario:ActivityScenario<MainActivity>,view:MapView,map:MapLibreMap) {
        val ready=CountDownLatch(1)
        scenario.onActivity { view.addOnDidBecomeIdleListener { ready.countDown() }; view.post { map.triggerRepaint() } }
        if(ready.await(700,TimeUnit.MILLISECONDS)) return
        // An already-idle renderer need not emit another transition after triggerRepaint.
        // In that case require three stable snapshots instead of waiting for a lost event.
        val deadline=SystemClock.uptimeMillis()+15_000
        var previous=snapshot(scenario,map)
        var stable=0
        while(SystemClock.uptimeMillis()<deadline) {
            SystemClock.sleep(120)
            val current=snapshot(scenario,map)
            if(difference(previous,current)<0.01) stable++ else stable=0
            previous.recycle()
            previous=current
            if(stable>=3) { previous.recycle(); return }
        }
        previous.recycle()
        fail("O mapa não estabilizou antes da captura dos blocos locais")
    }

    private fun snapshot(scenario:ActivityScenario<MainActivity>,map:MapLibreMap):Bitmap {
        val ready=CountDownLatch(1)
        val result=AtomicReference<Bitmap>()
        scenario.onActivity { map.snapshot { result.set(it); ready.countDown() } }
        assertTrue(ready.await(15,TimeUnit.SECONDS))
        return requireNotNull(result.get())
    }

    private fun difference(first:Bitmap,second:Bitmap):Double {
        assertEquals(first.width,second.width); assertEquals(first.height,second.height)
        var total=0L; var pixels=0
        for (y in 0 until first.height step 2) for(x in 0 until first.width step 2) {
            val a=first.getPixel(x,y); val b=second.getPixel(x,y)
            total+=kotlin.math.abs(Color.red(a)-Color.red(b))+kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b))
            pixels++
        }
        return total.toDouble()/(pixels*3)
    }

    private fun save(directory:File,name:String,image:Bitmap) {
        File(directory,name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }

    private fun findMap(view:View):MapView? {
        if(view is MapView) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        return null
    }
}

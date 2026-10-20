package br.com.palmasoffline

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class LocalMapAssetsDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun mapSourcesAndGlyphsAreBundledAndInternetIsNotRequested() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val style = context.assets.open("batalhao/style.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("asset://batalhao/fonts/{fontstack}/{range}.pbf", style.getString("glyphs"))
        val sources = style.getJSONObject("sources")
        for (source in sources.keys()) {
            val definition = sources.getJSONObject(source)
            assertFalse("Fonte externa no mapa offline: $source", definition.has("url"))
            val data = definition.get("data")
            if (data is JSONObject) continue // Route and selected positions are filled in memory.
            val path = data as String
            assertTrue(path.startsWith("asset://batalhao/"))
            context.assets.open(path.removePrefix("asset://")).use { assertTrue(it.read() >= 0) }
        }
        context.assets.open("batalhao/fonts/Open Sans Semibold/0-255.pbf").use { assertTrue(it.read() >= 0) }
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
    }

    @Test fun vectorLayersStayWithinVisualBoundsAndMatchTheirInventory() {
        val metadata = assetJson("metadata.json")
        val bounds = metadata.getJSONArray("visualBounds")
        val counts = metadata.getJSONObject("featureCounts")
        for (layer in listOf("region", "municipalities", "settlements", "settlement_areas",
            "access_roads", "roads", "areas", "waterways", "places")) {
            val collection = assetJson("$layer.geojson")
            assertEquals("FeatureCollection", collection.getString("type"))
            val features = collection.getJSONArray("features")
            assertTrue("Camada vazia: $layer", features.length() > 0)
            assertEquals("Inventário incompatível com $layer", counts.getInt(layer), features.length())
            for (index in 0 until features.length()) {
                val feature = features.getJSONObject(index)
                assertEquals("Feature", feature.getString("type"))
                val geometry = feature.getJSONObject("geometry")
                assertPositionsInBounds("$layer/$index", geometry.getJSONArray("coordinates"), bounds)
            }
        }

        val municipalities = assetJson("municipalities.geojson").getJSONArray("features")
        assertEquals("O pacote deve conter somente os 11 municípios do 8º BPM", 11, municipalities.length())
        val codes = (0 until municipalities.length()).map {
            municipalities.getJSONObject(it).getJSONObject("properties").getString("CD_MUN")
        }
        assertEquals("Municípios repetidos no pacote", 11, codes.toSet().size)
        assertTrue("Código municipal inválido", codes.all { it.matches(Regex("17\\d{5}")) })
    }

    @Test fun settlementReferencesKeepOnalicioAndExcludeTheRejectedPoint() {
        val features = assetJson("settlements.geojson").getJSONArray("features")
        val ids = mutableSetOf<Int>()
        var onalicio: JSONObject? = null
        for (index in 0 until features.length()) {
            val feature = features.getJSONObject(index)
            val properties = feature.getJSONObject("properties")
            val id = properties.getInt("id")
            assertTrue("Identificador de assentamento repetido: $id", ids.add(id))
            assertTrue("Assentamento sem nome: $id", properties.getString("nome").isNotBlank())
            assertTrue("Assentamento sem estado de revisão: $id",
                properties.getString("status_revisao").isNotBlank())
            assertFalse("Ponto reprovado incluído no pacote: $id",
                properties.getString("status_revisao").equals("incorreto", ignoreCase = true))
            assertEquals("Point", feature.getJSONObject("geometry").getString("type"))
            if (id == 44) onalicio = feature
        }
        assertFalse("O ponto 40, marcado incorreto, reapareceu no mapa", 40 in ids)
        assertNotNull("A referência do Onalício Barros não foi incluída", onalicio)
        val coordinates = onalicio!!.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(-49.9269444, coordinates.getDouble(0), 0.00001)
        assertEquals(-9.5666667, coordinates.getDouble(1), 0.00001)
    }

    @Test fun packagedVectorFilesMatchTheirIntegrityManifest() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val files = assetJson("metadata.json").getJSONObject("contentFiles")
        for (layer in listOf("region", "municipalities", "settlements", "settlement_areas",
            "access_roads", "roads", "areas", "waterways", "places")) {
            assertTrue("Camada sem verificação de integridade: $layer", files.has("batalhao/$layer.geojson"))
        }
        for (key in files.keys()) {
            val entry = files.getJSONObject(key)
            val path = key // Entries also record their repository provenance in "path".
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            assets.open(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    bytes += read
                }
            }
            assertEquals("Arquivo incompleto: $path", entry.getLong("bytes"), bytes)
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("Conteúdo incorreto: $path", entry.getString("sha256"), actualHash)
        }
    }

    private fun assetJson(name: String): JSONObject =
        InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("batalhao/$name").bufferedReader().use { JSONObject(it.readText()) }

    private fun assertPositionsInBounds(label: String, coordinates: JSONArray, bounds: JSONArray) {
        assertTrue("Geometria vazia em $label", coordinates.length() > 0)
        if (coordinates.get(0) is Number) {
            assertTrue("Coordenada incompleta em $label", coordinates.length() >= 2)
            val longitude = coordinates.getDouble(0)
            val latitude = coordinates.getDouble(1)
            // Allow rounding at the exported clip boundary (well below one meter).
            val tolerance = 0.000001
            assertTrue("Longitude fora do recorte visual em $label: $longitude",
                longitude.isFinite() && longitude >= bounds.getDouble(0) - tolerance &&
                    longitude <= bounds.getDouble(2) + tolerance)
            assertTrue("Latitude fora do recorte visual em $label: $latitude",
                latitude.isFinite() && latitude >= bounds.getDouble(1) - tolerance &&
                    latitude <= bounds.getDouble(3) + tolerance)
        } else {
            for (index in 0 until coordinates.length()) {
                assertPositionsInBounds(label, coordinates.getJSONArray(index), bounds)
            }
        }
    }
}

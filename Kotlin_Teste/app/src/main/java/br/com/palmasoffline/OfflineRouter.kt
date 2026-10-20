package br.com.palmasoffline

import android.content.Context
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class Maneuver(
    val instruction: String, val kilometers: Double, val seconds: Double,
    val beginShapeIndex: Int = 0, val endShapeIndex: Int = 0,
    val verbalInstruction: String? = null, val type: Int = 0,
)
data class RouteResult(val kilometers: Double, val seconds: Double, val maneuvers: List<Maneuver>, val geometry: List<Coordinates>)

/** One native actor. All requests and closing share the same lock. */
class OfflineRouter(context: Context) : Closeable {
    private val context = context.applicationContext
    private var engine: Valhalla? = null
    private var closed = false

    @Synchronized
    fun route(origin: Coordinates, destination: Coordinates): RouteResult {
        val trip = JSONObject(actor().routeRaw(request(origin, destination).toString())).getJSONObject("trip")
        return parseTrip(trip)
    }

    @Synchronized
    fun navigationJson(origin: Coordinates, destination: Coordinates, heading: Double? = null): String {
        val request = request(origin, destination)
            .put("format", "osrm").put("shape_format", "polyline6")
            .put("banner_instructions", true).put("voice_instructions", true)
        if (heading != null && heading.isFinite()) {
            request.getJSONArray("locations").getJSONObject(0)
                .put("heading", ((heading % 360) + 360) % 360).put("heading_tolerance", 45)
        }
        return actor().routeRaw(request.toString())
    }

    private fun actor(): Valhalla {
        check(!closed) { "Motor de roteamento encerrado." }
        return engine ?: initialize().also { engine = it }
    }

    private fun request(origin: Coordinates, destination: Coordinates): JSONObject {
        require(BatalhaoRegion.contains(origin) && BatalhaoRegion.contains(destination)) {
            "Origem e destino precisam estar dentro da região offline do batalhão."
        }
        fun point(coordinates: Coordinates) = JSONObject()
            .put("lat", coordinates.latitude).put("lon", coordinates.longitude)
            .put("search_cutoff", 100)
        return JSONObject().put("locations", JSONArray().put(point(origin)).put(point(destination)))
            .put("costing", "auto")
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", "pt-BR"))
    }

    private fun initialize(): Valhalla {
        val metadata = context.assets.open("batalhao/metadata.json").bufferedReader().use { JSONObject(it.readText()) }
        val expectedHash = metadata.getString("routingSha256")
        require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "Identificação inválida do pacote de navegação." }
        val tiles = File(context.filesDir, "batalhao_${expectedHash.take(16)}.tar")
        val expectedLength = context.assets.openFd("batalhao_tiles_v1.tar").use { it.length }
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        if (!tiles.isFile || tiles.length() != expectedLength || digest(tiles) != expectedHash) {
            require(context.filesDir.usableSpace > expectedLength) { "Espaço insuficiente para instalar os dados de roteamento." }
            val temporary = File(context.filesDir, "${tiles.name}.part")
            try {
                context.assets.open("batalhao_tiles_v1.tar").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.length() == expectedLength) { "A cópia dos tiles ficou incompleta." }
                check(digest(temporary) == expectedHash) { "O pacote de navegação falhou na verificação de integridade." }
                check(temporary.renameTo(tiles)) { "Não foi possível instalar os tiles." }
            } finally { temporary.delete() }
        }
        val actor = Valhalla(context, ValhallaConfigFactory.usingTileExtract(tiles.absolutePath))
        File(context.filesDir, "valhalla_tiles.tar").delete()
        return actor
    }

    @Synchronized override fun close() { closed = true; engine?.close(); engine = null }

    companion object {
        /** Maneuver indexes are local to each leg; convert them to the merged shape. */
        fun parseTrip(trip: JSONObject): RouteResult {
            val summary = trip.getJSONObject("summary")
            val geometry = mutableListOf<Coordinates>()
            val maneuvers = mutableListOf<Maneuver>()
            val legs = trip.getJSONArray("legs")
            for (legIndex in 0 until legs.length()) {
                val leg = legs.getJSONObject(legIndex)
                val points = Polyline6.decode(leg.getString("shape"))
                require(points.isNotEmpty()) { "Trecho de rota sem geometria." }
                val shared = geometry.lastOrNull() == points.first()
                val offset = geometry.size - if (shared) 1 else 0
                geometry.addAll(if (shared) points.drop(1) else points)
                val instructions = leg.getJSONArray("maneuvers")
                for (index in 0 until instructions.length()) {
                    val item = instructions.getJSONObject(index)
                    val begin = item.getInt("begin_shape_index")
                    val end = item.getInt("end_shape_index")
                    require(begin in points.indices && end in begin until points.size) { "Índices de manobra inválidos." }
                    maneuvers += Maneuver(item.getString("instruction"), item.getDouble("length"), item.getDouble("time"),
                        begin + offset, end + offset, item.optString("verbal_pre_transition_instruction").takeIf { it.isNotBlank() }, item.getInt("type"))
                }
            }
            check(geometry.size >= 2) { "O motor não retornou uma geometria de rota válida." }
            return RouteResult(summary.getDouble("length"), summary.getDouble("time"), maneuvers, geometry)
        }
    }
}

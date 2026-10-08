package br.com.palmasoffline

import com.stadiamaps.ferrostar.core.CustomRouteProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.ferrostar.Route
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.createRouteFromOsrm

/** Calls only the native actor and decodes the extended OSRM response locally. */
class OfflineNavigationRouteProvider(private val router: OfflineRouter) : CustomRouteProvider {
    override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> = withContext(Dispatchers.IO) {
        require(waypoints.size == 1) { "O protótipo suporta um destino por sessão." }
        val from = Coordinates(userLocation.coordinates.lat, userLocation.coordinates.lng)
        val to = Coordinates(waypoints.single().coordinate.lat, waypoints.single().coordinate.lng)
        val heading = userLocation.courseOverGround?.takeIf {
            (userLocation.speed?.value ?: 0.0) >= 2.0 && (it.accuracy == null || it.accuracy!! <= 45u)
        }
            ?.degrees?.toDouble()
        decode(router.navigationJson(from, to, heading))
    }

    companion object {
        fun decode(json: String): List<Route> {
            val response = JSONObject(json)
            require(response.getString("code") == "Ok") { "O Valhalla não retornou uma rota de navegação válida." }
            val waypoints = response.getJSONArray("waypoints").toString().encodeToByteArray()
            val routes = response.getJSONArray("routes")
            check(routes.length() > 0) { "Nenhuma rota encontrada nos dados locais." }
            return (0 until routes.length()).map {
                createRouteFromOsrm(routes.getJSONObject(it).toString().encodeToByteArray(), waypoints, 6u).also { route ->
                    check(route.geometry.size >= 2 && route.steps.isNotEmpty()) { "Rota incompleta para navegação." }
                    check(route.steps.all { step -> step.geometry.isNotEmpty() }) { "Manobra sem geometria." }
                }
            }
        }
        fun preview(route: Route) = RouteResult(route.distance / 1000.0, route.steps.sumOf { it.duration },
            route.steps.map { Maneuver(it.instruction, it.distance / 1000.0, it.duration) },
            route.geometry.map { Coordinates(it.lat, it.lng) })
    }
}

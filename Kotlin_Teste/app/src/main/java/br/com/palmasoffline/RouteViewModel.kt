package br.com.palmasoffline

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stadiamaps.ferrostar.core.*
import com.stadiamaps.ferrostar.core.http.HttpClientProvider
import com.stadiamaps.ferrostar.core.http.IResponse

import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.location.toUserLocation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uniffi.ferrostar.*

data class RouteUiState(val busy: Boolean = false, val result: RouteResult? = null, val error: String? = null)
enum class NavigationPhase { PLANNING, ACQUIRING, CALCULATING, PREVIEW, NAVIGATING, RECALCULATING, PAUSED, ARRIVED }
data class SessionUiState(
    val phase: NavigationPhase = NavigationPhase.PLANNING,
    val simulated: Boolean = false,
    val message: String? = null,
    val voiceMessage: String? = null,
    val destination: Coordinates? = null,
    val recalculations: Int = 0,
    val gpsStatus: String? = null,
) {
    val navigating get() = phase == NavigationPhase.NAVIGATING || phase == NavigationPhase.RECALCULATING
    val showingNavigation get() = phase != NavigationPhase.PLANNING
}

class RouteViewModel @JvmOverloads constructor(
    application: Application,
    private val routeProviderOverride: CustomRouteProvider? = null,
) : AndroidViewModel(application), NavigationViewModel {
    private val router = OfflineRouter(application)
    private val generation = NavigationGeneration()
    private val mutableState = MutableStateFlow(RouteUiState())
    val state = mutableState.asStateFlow()
    private val mutableSession = MutableStateFlow(SessionUiState())
    val session = mutableSession.asStateFlow()
    private val mutableNavigation = MutableStateFlow(NavigationUiState.empty())
    override val navigationUiState = mutableNavigation.asStateFlow()
    private val mutableLocation = MutableStateFlow<Coordinates?>(null)
    val location = mutableLocation.asStateFlow()
    private val voice = OfflineVoice(application) { message -> viewModelScope.launch { mutableSession.update { it.copy(voiceMessage = message) } } }
    private val feed = NavigationLocationFeed()
    private val reroutePolicy = ReroutePolicy()
    private var core: FerrostarCore? = null
    private var coreJob: Job? = null
    private var routeJob: Job? = null
    private var simulationJob: Job? = null
    private var gpsJob: Job? = null
    private var gpsReceptionJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastLive: Location? = null
    private var lastLiveAcceptedAt: Long? = null
    private var lastFixProblem: String? = null
    private var previewRoute: Route? = null
    private var previewOrigin: Coordinates? = null
    private var previewOriginAccuracy = 0f
    private var acquiredDestination: Coordinates? = null
    private var foreground = false
    private var rerouteJob: Job? = null
    private var simulation: SimulatedLocationProvider? = null
    private var injectingDeviation = false
    private var deviationJob: Job? = null

    init {
        viewModelScope.launch { voice.muteState.collect { muted -> mutableNavigation.update { it.copy(isMuted = muted) } } }
    }

    fun onForeground() {
        foreground = true
        voice.start()
        if (mutableSession.value.phase == NavigationPhase.ACQUIRING) startGps()
    }

    fun onBackground() {
        foreground = false
        val current = mutableSession.value
        if (current.showingNavigation && current.phase != NavigationPhase.ARRIVED) {
            disposeSession()
            mutableState.update { it.copy(busy = false) }
            mutableSession.value = current.copy(phase = NavigationPhase.PAUSED, gpsStatus = null, message = "Navegação pausada ao sair do app. Retome para calcular a partir da posição atual.")
        } else stopGps()
        voice.shutdown()
    }

    fun clearResult() {
        if (mutableSession.value.showingNavigation) return
        generation.next()
        routeJob?.cancel()
        mutableState.value = RouteUiState()
    }

    fun calculate(originLat: String, originLon: String, destinationLat: String, destinationLon: String) {
        if (mutableSession.value.showingNavigation || mutableState.value.busy) return
        val ticket = generation.next()
        mutableState.value = RouteUiState(busy = true)
        routeJob = viewModelScope.launch {
            try {
                val origin = Coordinates.parse(originLat, originLon, "Origem")
                val destination = Coordinates.parse(destinationLat, destinationLon, "Destino")
                val result = withContext(Dispatchers.IO) { router.route(origin, destination) }
                if (generation.isCurrent(ticket)) mutableState.value = RouteUiState(result = result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation.isCurrent(ticket)) mutableState.value = RouteUiState(error = errorMessage(e)) }
        }
    }

    fun prepareLive(destination: Coordinates) {
        disposeSession()
        mutableState.update { it.copy(error = null, busy = false) }
        mutableSession.value = SessionUiState(phase = NavigationPhase.ACQUIRING, destination = destination, voiceMessage = mutableSession.value.voiceMessage,
            message = "Obtendo uma posição GPS precisa para calcular a rota…")
        if (!BatalhaoRegion.contains(destination)) { mutableSession.update { it.copy(message = "Destino fora da cobertura offline.") }; return }
        acquiredDestination = destination
        startGps()
    }

    fun permissionResult() { if (mutableSession.value.phase == NavigationPhase.ACQUIRING) startGps() }

    fun prepareSimulation(origin: Coordinates, destination: Coordinates) {
        disposeSession()
        prepare(origin, destination, true)
    }

    private fun hasPrecisePermission() = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startGps() {
        if (!foreground || gpsJob?.isActive == true) return
        if (!hasPrecisePermission()) {
            mutableSession.update { it.copy(message = "Permita localização precisa para navegar. A simulação funciona sem GPS.") }
            return
        }
        val manager = getApplication<Application>().getSystemService(LocationManager::class.java)
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            mutableSession.update { it.copy(message = "GPS desligado. Ative a localização e volte ao aplicativo.") }
            return
        }
        val source = GnssLocationProvider(getApplication())
        gpsReceptionJob = viewModelScope.launch {
            source.reception.collect { reception -> mutableSession.update { it.copy(gpsStatus = reception.description()) } }
        }
        gpsJob = viewModelScope.launch {
            try {
                source.locationUpdates(1000).collect { fix ->
                    val point = Coordinates(fix.latitude, fix.longitude)
                    val age = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
                    val problem = LocationPolicy.problem(point, fix.accuracy.toDouble().takeIf { fix.hasAccuracy() }, age)
                    if (problem != null) {
                        // Invalid readings never advance Ferrostar or trigger rerouting.
                        lastLive = null
                        lastFixProblem = problem
                        reroutePolicy.observe(false, SystemClock.elapsedRealtime())
                        mutableSession.update { it.copy(message = problem) }
                    } else {
                        lastFixProblem = null
                        lastLive = Location(fix)
                        lastLiveAcceptedAt = SystemClock.elapsedRealtime()
                        mutableLocation.value = point
                        mutableSession.update { it.copy(message = if (fix.accuracy > LocationPolicy.MAX_REROUTE_ACCURACY_METERS)
                            "GPS com precisão reduzida (±${fix.accuracy.toInt()} m). Recálculo automático aguarda melhor precisão." else null) }
                        if (!mutableSession.value.simulated) feed.publish(fix)
                        acquiredDestination?.let { destination ->
                            acquiredDestination = null
                            prepare(point, destination, false)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableSession.update { it.copy(message = "Não foi possível obter GPS: ${e.message}") } }
        }
        watchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lastLive = null
                    mutableSession.update { it.copy(message = "GPS desligado. Ative a localização para continuar.") }
                } else if (lastLiveAcceptedAt == null || SystemClock.elapsedRealtime() - lastLiveAcceptedAt!! > LocationPolicy.MAX_AGE_MILLIS) {
                    lastLive = null
                    mutableSession.update { it.copy(message = lastFixProblem ?: "Aguardando sinal GPS preciso…") }
                    reroutePolicy.observe(false, SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private fun stopGps() { gpsReceptionJob?.cancel(); gpsReceptionJob = null; mutableSession.update { it.copy(gpsStatus = null) }; gpsJob?.cancel(); gpsJob = null; watchdogJob?.cancel(); watchdogJob = null; lastLive = null; lastLiveAcceptedAt = null; lastFixProblem = null }

    private fun newCore(ticket: Long): FerrostarCore {
        val noNetwork = object : HttpClientProvider {
            override suspend fun call(request: RouteRequest): IResponse = error("Este navegador utiliza somente rotas locais.")
        }
        val controller = FerrostarCore(customRouteProvider = routeProviderOverride ?: OfflineNavigationRouteProvider(router), httpClient = noNetwork,
            locationProvider = feed, navigationControllerConfig = navigationConfig())
        controller.spokenInstructionObserver = voice
        // Keep lifecycle and failure reporting under the app's cancellable session scope.
        // Deviation and maneuver progression still come exclusively from Ferrostar.
        controller.deviationHandler = RouteDeviationHandler { _, _, _ -> CorrectiveAction.DoNothing }
        coreJob?.cancel()
        coreJob = viewModelScope.launch {
            controller.state.collect { nav ->
                if (!generation.isCurrent(ticket) || !foreground) return@collect
                mutableNavigation.value = NavigationUiState.fromFerrostar(nav, voice.isMuted)
                if (nav.tripState is TripState.Complete && mutableSession.value.navigating) {
                    val current = mutableSession.value
                    disposeSession()
                    mutableSession.value = current.copy(phase = NavigationPhase.ARRIVED, message = "Você chegou ao destino.")
                    mutableState.update { it.copy(busy = false) }
                } else if (mutableSession.value.navigating && nav.tripState is TripState.Navigating) {
                    val now = SystemClock.elapsedRealtime()
                    val canUsePosition = mutableSession.value.simulated || validLive()?.let { LocationPolicy.canReroute(it.accuracy.toDouble()) } == true
                    if (reroutePolicy.observe(canUsePosition && (nav.tripState as TripState.Navigating).deviation is RouteDeviation.Deviation, now)) {
                        requestReroute(ticket, controller)
                    }
                }
            }
        }
        return controller
    }

    private fun prepare(origin: Coordinates, destination: Coordinates, simulated: Boolean) {
        val ticket = generation.next()
        routeJob?.cancel(); coreJob?.cancel(); core?.stopNavigation()
        mutableSession.update { it.copy(phase = NavigationPhase.CALCULATING, simulated = simulated, destination = destination, message = null, recalculations = 0) }
        mutableState.update { it.copy(busy = true, error = null) }
        feed.clear()
        val startingLocation = if (simulated) simulatedLocation(origin) else validLive()
        if (startingLocation == null) {
            mutableSession.update { it.copy(phase = NavigationPhase.ACQUIRING, message = "Aguardando uma nova posição GPS…") }
            mutableState.update { it.copy(busy = false) }
            acquiredDestination = destination
            startGps()
            return
        }
        feed.publish(startingLocation)
        val navigator = newCore(ticket).also { core = it }
        routeJob = viewModelScope.launch {
            try {
                val route = navigator.getRoutes(startingLocation.toUserLocation(), listOf(Waypoint(GeographicCoordinate(destination.latitude, destination.longitude), WaypointKind.BREAK))).first()
                if (!generation.isCurrent(ticket) || !foreground) return@launch
                previewRoute = route; previewOrigin = origin; previewOriginAccuracy = startingLocation.accuracy
                mutableState.value = RouteUiState(result = OfflineNavigationRouteProvider.preview(route))
                mutableSession.update { it.copy(phase = NavigationPhase.PREVIEW, message = if (simulated) "Rota pronta para simulação." else "Rota calculada a partir do GPS. Toque em iniciar.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation.isCurrent(ticket)) {
                    mutableState.update { it.copy(busy = false, error = errorMessage(e)) }
                    mutableSession.update { it.copy(phase = NavigationPhase.PREVIEW, message = errorMessage(e)) }
                }
            }
        }
    }

    private fun validLive(): Location? = lastLive?.takeIf {
        LocationPolicy.problem(Coordinates(it.latitude, it.longitude), it.accuracy.toDouble(),
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000) == null
    }

    fun beginNavigation() {
        val route = previewRoute ?: return
        val navigator = core ?: return
        if (!foreground) return
        if (!mutableSession.value.simulated) {
            val fix = validLive()
            if (fix == null) { mutableSession.update { it.copy(message = "Aguarde uma posição GPS precisa antes de iniciar.") }; return }
            val original = previewOrigin ?: return
            val originalFix = simulatedLocation(original)
            if (fix.distanceTo(originalFix) > maxOf(25f, fix.accuracy + previewOriginAccuracy)) {
                mutableSession.value.destination?.let { prepare(Coordinates(fix.latitude, fix.longitude), it, false) }
                return
            }
            feed.publish(fix)
        }
        mutableSession.update { it.copy(phase = NavigationPhase.NAVIGATING, message = null) }
        voice.sessionActive = true
        navigator.startNavigation(route)
        if (mutableSession.value.simulated) startSimulation(route)
    }

    private fun startSimulation(route: Route) {
        simulationJob?.cancel()
        val source = SimulatedLocationProvider(scope = viewModelScope, warpFactor = 3u, initialLocation = simulatedLocation(Coordinates(route.geometry.first().lat, route.geometry.first().lng)))
        simulation = source
        source.setRoute(route)
        simulationJob = viewModelScope.launch {
            source.locationUpdates().collect {
                if (!injectingDeviation && mutableSession.value.navigating && mutableSession.value.simulated) {
                    feed.publish(it)
                    mutableLocation.value = Coordinates(it.latitude, it.longitude)
                }
            }
        }
    }

    private fun requestReroute(ticket: Long, navigator: FerrostarCore) {
        if (rerouteJob?.isActive == true) return
        val destination = mutableSession.value.destination ?: return
        reroutePolicy.attempted(SystemClock.elapsedRealtime())
        rerouteJob = viewModelScope.launch {
            mutableSession.update { it.copy(phase = NavigationPhase.RECALCULATING, message = "Recalculando com os dados locais…") }
            try {
                val from = if (mutableSession.value.simulated) feed.lastLocation() else validLive()
                require(from != null && (mutableSession.value.simulated || LocationPolicy.canReroute(from.accuracy.toDouble()))) { "Aguarde GPS com precisão de até 35 m para recalcular." }
                val route = navigator.getRoutes(from.toUserLocation(), listOf(Waypoint(GeographicCoordinate(destination.latitude, destination.longitude), WaypointKind.BREAK))).first()
                if (!generation.isCurrent(ticket) || core !== navigator || !foreground) return@launch
                previewRoute = route
                if (!mutableSession.value.simulated && validLive()?.let { LocationPolicy.canReroute(it.accuracy.toDouble()) } != true) throw IllegalStateException("O sinal GPS perdeu precisão durante o recálculo.")
                navigator.replaceRoute(route)
                mutableState.update { it.copy(result = OfflineNavigationRouteProvider.preview(route), error = null) }
                mutableSession.update { it.copy(phase = NavigationPhase.NAVIGATING, message = null, recalculations = it.recalculations + 1) }
                if (mutableSession.value.simulated) { injectingDeviation = false; startSimulation(route) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation.isCurrent(ticket)) {
                    injectingDeviation = false
                    mutableState.update { it.copy(error = "Falha no recálculo: ${errorMessage(e)}") }
                    mutableSession.update { it.copy(phase = NavigationPhase.NAVIGATING, message = "Não foi possível recalcular. A rota anterior foi mantida.") }
                }
            }
        }
    }

    /** A debug-only explicit deviation, useful on the selected street grid without driving. */
    fun simulateDeviation() {
        if (!mutableSession.value.simulated || !mutableSession.value.navigating || injectingDeviation) return
        injectingDeviation = true
        deviationJob = viewModelScope.launch {
            val current = feed.lastLocation() ?: return@launch
            val point = Coordinates(current.latitude, current.longitude + 0.0015)
            if (!BatalhaoRegion.contains(point)) { injectingDeviation = false; return@launch }
            repeat(7) {
                if (!mutableSession.value.navigating || !injectingDeviation) return@launch
                val fix = simulatedLocation(point)
                fix.bearing = 90f
                feed.publish(fix)
                mutableLocation.value = point
                delay(1000)
            }
            injectingDeviation = false
        }
    }

    fun resumeFromPause() {
        val current = mutableSession.value
        val destination = current.destination ?: return
        if (current.simulated) {
            val origin = previewOrigin ?: return
            prepareSimulation(origin, destination)
        } else prepareLive(destination)
    }

    override fun stopNavigation() {
        disposeSession()
        mutableState.update { it.copy(busy = false, error = null) }
        mutableSession.update { SessionUiState(voiceMessage = it.voiceMessage) }
        mutableNavigation.value = NavigationUiState.empty()
        mutableLocation.value = null
    }

    override fun toggleMute() { voice.setMuted(!voice.isMuted) }
    override fun setDestination(destination: String?) { mutableNavigation.update { it.copy(destination = destination) } }

    private fun disposeSession() {
        generation.next()
        routeJob?.cancel(); routeJob = null
        rerouteJob?.cancel(); rerouteJob = null
        simulationJob?.cancel(); simulationJob = null
        deviationJob?.cancel(); deviationJob = null
        simulation = null; injectingDeviation = false
        coreJob?.cancel(); coreJob = null
        voice.sessionActive = false
        core?.stopNavigation(); core = null
        voice.stopAndClearQueue()
        stopGps()
        acquiredDestination = null
        previewRoute = null
        feed.clear()
        reroutePolicy.reset()
    }

    override fun onCleared() {
        disposeSession()
        voice.shutdown()
        Thread({ router.close() }, "offline-router-close").start()
    }

    companion object {
        fun navigationConfig() = NavigationControllerConfig(
            WaypointAdvanceMode.WaypointWithinRange(20.0),
            stepAdvanceDistanceEntryAndExit(25u, 5u, 50u),
            stepAdvanceDistanceToEndOfStep(20u, 35u),
            RouteDeviationTracking.StaticThreshold(35u, 45.0), CourseFiltering.SNAP_TO_ROUTE)
        fun simulatedLocation(point: Coordinates) = Location("PalmasSimulado").apply {
            latitude = point.latitude; longitude = point.longitude; accuracy = 5f
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        private fun errorMessage(e: Exception) = e.message?.take(220) ?: "Não foi possível calcular com os dados locais."
    }
}

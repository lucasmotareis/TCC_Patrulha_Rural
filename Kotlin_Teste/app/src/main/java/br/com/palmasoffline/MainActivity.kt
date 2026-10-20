package br.com.palmasoffline

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import com.stadiamaps.ferrostar.composeui.views.components.TripProgressView
import com.stadiamaps.ferrostar.ui.formatters.DistanceMeasurementSystem
import com.stadiamaps.ferrostar.ui.formatters.LocalizedDistanceFormatter
import android.icu.util.ULocale
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var controller: PalmasMapController
    private val model: RouteViewModel by viewModels()
    private var mapStatus by mutableStateOf<String?>("Carregando mapa local…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE))
        MapLibre.getInstance(this)
        MapLibre.setConnected(false)
        controller = PalmasMapController(MapView(this), savedInstanceState) { mapStatus = it }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006B5E),
                secondaryContainer = Color(0xFFD6EEE7), onSecondaryContainer = Color(0xFF003D32), background = Color(0xFFF5F8F7))) {
                RouteScreen(controller, mapStatus, model)
            }
        }
    }
    override fun onStart() { super.onStart(); controller.view.onStart(); model.onForeground() }
    override fun onResume() { super.onResume(); controller.view.onResume() }
    override fun onPause() { controller.view.onPause(); super.onPause() }
    override fun onStop() { model.onBackground(); controller.view.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { controller.saveState(outState); super.onSaveInstanceState(outState) }
    @Suppress("DEPRECATION") override fun onLowMemory() { super.onLowMemory(); controller.view.onLowMemory() }
    override fun onDestroy() { controller.destroy(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
private fun RouteScreen(controller: PalmasMapController, mapStatus: String?, model: RouteViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val navigation by model.navigationUiState.collectAsStateWithLifecycle()
    val livePoint by model.location.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.permissionResult() }
    var originLat by rememberSaveable { mutableStateOf("-10.1417377") }
    var originLon by rememberSaveable { mutableStateOf("-49.1781853") }
    var destinationLat by rememberSaveable { mutableStateOf("-10.119233731352583") }
    var destinationLon by rememberSaveable { mutableStateOf("-49.18961793050047") }
    var destinationName by rememberSaveable { mutableStateOf<String?>(null) }
    var destinationReviewMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectOrigin by rememberSaveable { mutableStateOf(true) }
    var showCoordinates by rememberSaveable { mutableStateOf(false) }
    var showDirections by rememberSaveable { mutableStateOf(false) }
    var showSatelliteInfo by rememberSaveable { mutableStateOf(false) }
    val satellite = controller.satelliteMap
    val showSatellite = controller.satelliteEnabled
    val origin = coordinateOrNull(originLat, originLon)
    val destination = coordinateOrNull(destinationLat, destinationLon)
    val formatter = remember { LocalizedDistanceFormatter(ULocale("pt_BR"), DistanceMeasurementSystem.SI) }
    if (session.showingNavigation && session.phase != NavigationPhase.PAUSED && session.phase != NavigationPhase.ARRIVED) KeepScreenOnDisposableEffect()

    // Read snapshots in this composition scope. Reading delegated state only inside
    // SideEffect does not subscribe this scope; child panels can recompose while
    // the native map never receives the new route.
    val mapState = state
    val mapSession = session
    val mapNavigation = navigation
    val mapLivePoint = livePoint
    SideEffect {
        controller.onPointSelected = { point ->
            if (!mapState.busy && !mapSession.showingNavigation) {
                model.clearResult()
                if (selectOrigin) { originLat = point.latitude.toString(); originLon = point.longitude.toString(); selectOrigin = false }
                else {
                    destinationLat = point.latitude.toString()
                    destinationLon = point.longitude.toString()
                    destinationName = null
                    destinationReviewMessage = null
                }
            }
        }
        controller.onSettlementSelected = { settlement ->
            if (!mapState.busy && !mapSession.showingNavigation) {
                model.clearResult()
                destinationLat = settlement.coordinates.latitude.toString()
                destinationLon = settlement.coordinates.longitude.toString()
                destinationName = settlement.name
                destinationReviewMessage = settlement.reviewMessage
                selectOrigin = false
            }
        }
        val displayedLocation = mapNavigation.location ?: mapLivePoint?.let {
            uniffi.ferrostar.UserLocation(uniffi.ferrostar.GeographicCoordinate(it.latitude, it.longitude), 5.0, null, java.time.Instant.now(), null)
        }
        controller.updateNavigation(if (mapSession.showingNavigation) displayedLocation else null, mapSession.navigating)
        controller.update(if (mapSession.navigating) null else if (mapSession.showingNavigation && !mapSession.simulated) mapLivePoint else origin, destination, mapState.result)
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!session.showingNavigation) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Patrulha Rural", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("8º BPM · Vale do Araguaia · Offline", style = MaterialTheme.typography.bodySmall)
                    if (satellite != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(!showSatellite, { controller.setSatelliteEnabled(false) }, label = { Text("Vetorial") })
                            FilterChip(showSatellite, { controller.setSatelliteEnabled(true) }, label = { Text(if (satellite.regional) "Satélite" else "Satélite (amostra)") })
                            TextButton(onClick = { showSatelliteInfo = true }, contentPadding = PaddingValues(4.dp)) { Text("Info") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selectOrigin, { selectOrigin = true }, label = { Text("A · Origem") }, enabled = !state.busy)
                        FilterChip(!selectOrigin, { selectOrigin = false }, label = { Text("B · Destino") }, enabled = !state.busy)
                    }
                    Text(if (state.busy) "Calculando com os dados locais…" else "Toque no mapa para escolher ${if (selectOrigin) "a origem" else "o destino"}.", style = MaterialTheme.typography.bodySmall)
                    Text("Toque em um assentamento para defini-lo como destino.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { controller.view }, modifier = Modifier.fillMaxSize())
                if (showSatellite && satellite != null && !session.showingNavigation) {
                    Surface(Modifier.align(Alignment.TopStart).padding(6.dp).clickable { showSatelliteInfo = true },
                        color = Color.White.copy(alpha = 0.92f), shape = MaterialTheme.shapes.extraSmall) {
                        Text("CBERS-4A · ${satellite.displayDate} · ${if (satellite.regional) "8º BPM" else "cobertura parcial"}",
                            Modifier.padding(horizontal = 6.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (session.showingNavigation) {
                    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = MaterialTheme.shapes.small, color = if (session.simulated) Color(0xFFFFE3A7) else MaterialTheme.colorScheme.primary,
                            contentColor = if (session.simulated) Color(0xFF402A00) else Color.White) {
                            Text(if (session.simulated) "SIMULAÇÃO · posições virtuais" else "NAVEGAÇÃO · GPS real", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                        }
                        navigation.visualInstruction?.let { instruction ->
                            InstructionsView(instructions = instruction, distanceToNextManeuver = navigation.progress?.distanceToNextManeuver,
                                distanceFormatter = formatter, remainingSteps = navigation.remainingSteps, modifier = Modifier.heightIn(max = 230.dp))
                        }
                        if (!session.simulated) session.gpsStatus?.let { StatusCard(it) }
                        session.message?.let { StatusCard(it) }
                        state.error?.takeIf { it != session.message }?.let { StatusCard(it) }
                    }
                }
                Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!session.showingNavigation) OutlinedButton(
                        onClick = if (showSatellite) controller::fitSatelliteSample else controller::fitRegion,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Text(if (showSatellite && satellite?.regional != true) "Ver amostra" else "Área do 8º BPM")
                    }
                    if (session.navigating) OutlinedButton(onClick = controller::recenter,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)) { Text("Recentralizar") }
                    OutlinedButton(onClick = controller::fitRoute,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)) { Text("Enquadrar") }
                }
                mapStatus?.let { Surface(Modifier.align(Alignment.Center).padding(12.dp).widthIn(max = 260.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) { Text(it, Modifier.padding(12.dp)) } }
                Surface(Modifier.align(Alignment.BottomStart).padding(6.dp).widthIn(max = 150.dp)
                    .clickable(enabled = satellite != null) { showSatelliteInfo = true }, color = Color.White.copy(alpha = 0.9f)) {
                    Text(if (showSatellite) "© OpenStreetMap · ODbL\nIBGE · CIGMA/INCRA\nCBERS-4A/INPE · CC BY 4.0"
                        else "© OpenStreetMap · ODbL\nIBGE · CIGMA/INCRA",
                        Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    destinationName?.let { Text("Destino: $it", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium) }
                    destinationReviewMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (session.showingNavigation) {
                        if (state.busy || session.phase == NavigationPhase.RECALCULATING) LinearProgressIndicator(Modifier.fillMaxWidth())
                        navigation.progress?.let { progress -> TripProgressView(progress = progress, distanceFormatter = formatter, onTapExit = model::stopNavigation, modifier = Modifier.fillMaxWidth()) }
                        if (session.phase == NavigationPhase.PREVIEW) {
                            state.result?.let { Text("${decimal(it.kilometers)} km · ${decimal(it.seconds / 60)} min", style = MaterialTheme.typography.titleLarge) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = model::stopNavigation) { Text("Voltar") }
                                Button(onClick = model::beginNavigation, enabled = !state.busy && state.error == null, modifier = Modifier.weight(1f)) {
                                    Text(if (session.simulated) "Iniciar simulação" else "Iniciar navegação")
                                }
                            }
                        } else if (session.phase == NavigationPhase.PAUSED) {
                            Button(onClick = model::resumeFromPause, modifier = Modifier.fillMaxWidth()) { Text("Retomar") }
                        } else if (session.phase == NavigationPhase.ACQUIRING) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text("Permitir GPS") }
                                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Ativar GPS") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = model::permissionResult) { Text("Verificar localização") }
                                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"))) }) { Text("Abrir permissões") }
                            }
                        }
                        if (session.navigating) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = model::toggleMute, enabled = session.voiceMessage == null) { Text(if (navigation.isMuted == true) "Ativar voz" else "Silenciar") }
                                if (session.simulated) OutlinedButton(onClick = model::simulateDeviation, enabled = session.phase == NavigationPhase.NAVIGATING) { Text("Simular desvio") }
                            }
                        }
                        if (session.showingNavigation && session.phase != NavigationPhase.PREVIEW && navigation.progress == null) {
                            OutlinedButton(onClick = model::stopNavigation, modifier = Modifier.fillMaxWidth()) { Text(if (session.phase == NavigationPhase.ARRIVED) "Concluir" else "Encerrar") }
                        }
                        if (session.phase == NavigationPhase.RECALCULATING && navigation.progress != null) {
                            TextButton(onClick = model::stopNavigation) { Text("Encerrar recálculo") }
                        }
                        session.voiceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        state.result?.let { result ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${decimal(result.kilometers)} km · ${decimal(result.seconds / 60)} min", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { showDirections = true }) { Text("Instruções") }
                            }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showCoordinates = true }, enabled = !state.busy) { Text("Coordenadas") }
                            Button(onClick = { model.calculate(originLat, originLon, destinationLat, destinationLon) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Calcular rota") }
                        }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    destination?.let { model.prepareLive(it); permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }
                                }, enabled = !state.busy && destination != null, modifier = Modifier.weight(1f)) { Text("Navegar por GPS") }
                                OutlinedButton(onClick = { if (origin != null && destination != null) model.prepareSimulation(origin, destination) },
                                    enabled = !state.busy && origin != null && destination != null) { Text("Simular") }
                            }

                    }
                }
            }
        }
    }
    if (showCoordinates && !session.showingNavigation) {
        ModalBottomSheet(onDismissRequest = { showCoordinates = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Coordenadas", style = MaterialTheme.typography.titleLarge)
                Text("Use pontos dentro da cobertura offline do 8º BPM, no Vale do Araguaia. A e B também podem ser escolhidos no mapa.")
                CoordinateFields("A · Origem", originLat, originLon, !state.busy, { originLat = it; model.clearResult() }, { originLon = it; model.clearResult() })
                CoordinateFields("B · Destino", destinationLat, destinationLon, !state.busy,
                    { destinationLat = it; destinationName = null; destinationReviewMessage = null; model.clearResult() },
                    { destinationLon = it; destinationName = null; destinationReviewMessage = null; model.clearResult() })
                Button(onClick = { showCoordinates = false }, modifier = Modifier.fillMaxWidth()) { Text("Voltar ao mapa") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (showDirections && !session.showingNavigation) {
        ModalBottomSheet(onDismissRequest = { showDirections = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Instruções", style = MaterialTheme.typography.titleLarge)
                state.result?.maneuvers?.forEachIndexed { index, maneuver ->
                    Text("${index + 1}. ${maneuver.instruction}", fontWeight = FontWeight.Medium)
                    Text("${decimal(maneuver.kilometers * 1000, 0)} m · ${decimal(maneuver.seconds, 0)} s")
                    HorizontalDivider()
                }
            }
        }
    }
    if (showSatelliteInfo && satellite != null) {
        fun openLink(url: String) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                .onFailure { Toast.makeText(context, "Nenhum aplicativo disponível para abrir o link.", Toast.LENGTH_SHORT).show() }
        }
        AlertDialog(
            onDismissRequest = { showSatelliteInfo = false },
            title = { Text(satellite.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (satellite.regional) "Imagens de ${satellite.displayDate}. Pacote offline dos 11 municípios do 8º BPM."
                        else "Imagem de ${satellite.displayDate}. Amostra offline com cobertura parcial do 8º BPM.")
                    Text("Imagens fusionadas pelo INPE, recortadas e reprojetadas para o aplicativo.", style = MaterialTheme.typography.bodySmall)
                    if (satellite is OfflineSatellitePackage) {
                        Text("Mapa no celular: ${decimal(satellite.tileBytes / 1_000_000_000.0, 2)} GB", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (satellite.regional) "As estradas, os assentamentos e a rota continuam sobre a imagem. As cenas têm datas diferentes; as condições atuais devem ser verificadas em campo."
                        else "As estradas, os assentamentos e a rota continuam sobre a imagem. Fora da amostra, o mapa permanece vetorial.")
                    Text(satellite.attribution, style = MaterialTheme.typography.bodySmall)
                    Text("Base vetorial: © OpenStreetMap · ODbL; IBGE; CIGMA/INCRA.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { openLink(satellite.sourceUrl) }) { Text("Fonte da imagem") }
                        TextButton(onClick = { openLink(satellite.licenseUrl) }) { Text("Licença CC BY 4.0") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSatelliteInfo = false }) { Text("Fechar") } },
        )
    }
}

@Composable private fun StatusCard(message: String) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) { Text(message, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
}
@Composable private fun CoordinateFields(label: String, latitude: String, longitude: String, enabled: Boolean, onLatitude: (String) -> Unit, onLongitude: (String) -> Unit) {
    Text(label, fontWeight = FontWeight.Bold)
    OutlinedTextField(latitude, onLatitude, label = { Text("Latitude") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(longitude, onLongitude, label = { Text("Longitude") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
}
private fun coordinateOrNull(latitude: String, longitude: String): Coordinates? = runCatching { Coordinates.parse(latitude, longitude, "Ponto") }.getOrNull()
private fun decimal(value: Double, digits: Int = 1): String = String.format(Locale.forLanguageTag("pt-BR"), "%.${digits}f", value)

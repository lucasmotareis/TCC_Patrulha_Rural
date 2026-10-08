package br.com.palmasoffline

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class GnssReception(
    val started: Boolean = false,
    val visibleSatellites: Int = 0,
    val usedSatellites: Int = 0,
    val accuracyMeters: Float? = null,
    val satelliteStatusAvailable: Boolean = true,
) {
    fun description(): String {
        val satellites = if (satelliteStatusAvailable) "$usedSatellites/$visibleSatellites satélites em uso" else "estatística de satélites indisponível"
        val accuracy = accuracyMeters?.takeIf { it.isFinite() && it > 0 }?.let { "±${it.roundToInt()} m" } ?: "aguardando posição"
        return "GPS por satélite · $satellites · $accuracy"
    }
}

/** Requests GNSS explicitly. Android's default fused request can stay balanced and never activate GNSS. */
class GnssLocationProvider(private val context: Context) : NavigationLocationProviding {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val mutableReception = MutableStateFlow(GnssReception())
    val reception = mutableReception.asStateFlow()

    @SuppressLint("MissingPermission")
    override suspend fun lastLocation(): Location? = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMillis: Long): Flow<Location> = callbackFlow {
        val callback = object : GnssStatus.Callback() {
            override fun onStarted() { mutableReception.update { it.copy(started = true) } }
            override fun onStopped() { mutableReception.update { it.copy(started = false, usedSatellites = 0) } }
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
                mutableReception.update { it.copy(visibleSatellites = status.satelliteCount, usedSatellites = used) }
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                mutableReception.update { it.copy(accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() }) }
                trySend(Location(location))
            }
            override fun onProviderDisabled(provider: String) { mutableReception.update { it.copy(started = false, usedSatellites = 0) } }
            override fun onProviderEnabled(provider: String) { mutableReception.update { it.copy(started = true) } }
        }
        try {
            val registered = manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            mutableReception.update { it.copy(satelliteStatusAvailable = registered) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = LocationRequest.Builder(intervalMillis)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(intervalMillis).setMaxUpdateDelayMillis(0).build()
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, ContextCompat.getMainExecutor(context), listener)
            } else {
                @Suppress("DEPRECATION")
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMillis, 0f, listener, Looper.getMainLooper())
            }
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { listener.onLocationChanged(it) }
            awaitClose {
                manager.removeUpdates(listener)
                manager.unregisterGnssStatusCallback(callback)
                mutableReception.value = GnssReception()
            }
        } catch (e: Exception) {
            manager.removeUpdates(listener)
            manager.unregisterGnssStatusCallback(callback)
            throw e
        }
    }
}

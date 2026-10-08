package br.com.palmasoffline

import android.location.Location
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Only vetted locations enter the navigator. Simulation and live tracking never share producers. */
class NavigationLocationFeed : NavigationLocationProviding {
    private val updates = MutableSharedFlow<Location>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var latest: Location? = null
    fun publish(location: Location) { latest = Location(location); updates.tryEmit(Location(location)) }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clear() { latest = null; updates.resetReplayCache() }
    override suspend fun lastLocation(): Location? = latest?.let { Location(it) }
    override fun locationUpdates(intervalMillis: Long): Flow<Location> = updates
}

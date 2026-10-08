package br.com.palmasoffline

import java.util.concurrent.atomic.AtomicLong

/** Invalidates work that the synchronous native engine cannot interrupt. */
class NavigationGeneration {
    private val serial = AtomicLong()
    fun next(): Long = serial.incrementAndGet()
    fun isCurrent(ticket: Long): Boolean = serial.get() == ticket
}

object LocationPolicy {
    const val MAX_AGE_MILLIS = 12_000L
    const val MAX_ACCURACY_METERS = 50.0
    const val MAX_REROUTE_ACCURACY_METERS = 35.0
    fun canReroute(accuracy: Double): Boolean = accuracy.isFinite() && accuracy > 0 && accuracy <= MAX_REROUTE_ACCURACY_METERS
    fun problem(point: Coordinates, accuracy: Double?, ageMillis: Long): String? = when {
        !point.latitude.isFinite() || !point.longitude.isFinite() -> "Localização inválida."
        ageMillis < -1000 || ageMillis > MAX_AGE_MILLIS -> "Sinal GPS desatualizado. Aguarde uma nova posição."
        accuracy == null || !accuracy.isFinite() || accuracy <= 0 || accuracy > MAX_ACCURACY_METERS -> "Aguardando uma posição dos satélites com precisão de até 50 m."
        !PalmasRegion.contains(point) -> "Você está fora da cobertura offline de Palmas."
        else -> null
    }
}

/** Debounces the deviation result supplied by Ferrostar, without implementing map matching. */
class ReroutePolicy {
    private var since: Long? = null
    private var samples = 0
    private var lastAttempt: Long? = null
    fun observe(offRoute: Boolean, nowMillis: Long): Boolean {
        if (!offRoute) { since = null; samples = 0; return false }
        if (since == null) since = nowMillis
        samples++
        return samples >= 3 && nowMillis - since!! >= 3000 &&
            (lastAttempt == null || nowMillis - lastAttempt!! >= 10_000)
    }
    fun attempted(nowMillis: Long) { lastAttempt = nowMillis; since = null; samples = 0 }
    fun reset() { since = null; samples = 0; lastAttempt = null }
}

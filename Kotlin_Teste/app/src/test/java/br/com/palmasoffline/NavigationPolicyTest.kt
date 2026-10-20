package br.com.palmasoffline

import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    private val palmas = Coordinates(-10.1752474, -48.886759)
    @Test fun acceptsFreshPrecisePositionInCoverage() { assertNull(LocationPolicy.problem(palmas, 8.0, 1000)) }
    @Test fun rejectsOldImpreciseAndOutOfCoverageFixes() {
        assertNotNull(LocationPolicy.problem(palmas, 8.0, 12_001))
        assertNotNull(LocationPolicy.problem(palmas, 51.0, 0))
        assertNotNull(LocationPolicy.problem(palmas, null, 0))
        assertNotNull(LocationPolicy.problem(palmas, Double.NaN, 0))
        assertNotNull(LocationPolicy.problem(Coordinates(-23.55, -46.63), 5.0, 0))
    }
    @Test fun moderateAccuracyCanNavigateButCannotTriggerAutomaticRerouting() {
        assertNull(LocationPolicy.problem(palmas, 45.0, 500))
        assertNull(LocationPolicy.problem(palmas, 50.0, 500))
        assertFalse(LocationPolicy.canReroute(45.0))
        assertFalse(LocationPolicy.canReroute(100.0))
        assertFalse(LocationPolicy.canReroute(Double.NaN))
        assertTrue(LocationPolicy.canReroute(15.0))
    }
    @Test fun discardedNativeWorkCannotUpdateStoppedOrNewSession() {
        val generation = NavigationGeneration()
        val initialRequest = generation.next()
        assertTrue(generation.isCurrent(initialRequest))
        generation.next() // Stop while a non-cancellable native request is in flight.
        assertFalse(generation.isCurrent(initialRequest))
        val newerSession = generation.next()
        assertFalse(generation.isCurrent(initialRequest))
        assertTrue(generation.isCurrent(newerSession))
    }
    @Test fun oneDeviationSampleOrReturningToRouteDoesNotRecalculate() {
        val policy = ReroutePolicy()
        assertFalse(policy.observe(true, 0))
        assertFalse(policy.observe(true, 1000))
        assertFalse(policy.observe(false, 2000))
        assertFalse(policy.observe(true, 3000))
        assertFalse(policy.observe(true, 4000))
        assertFalse(policy.observe(true, 5000))
        assertTrue(policy.observe(true, 6000))
    }
    @Test fun persistentDeviationRecalculatesAndFailuresHaveCooldown() {
        val policy = ReroutePolicy()
        assertFalse(policy.observe(true, 0))
        assertFalse(policy.observe(true, 1000))
        assertFalse(policy.observe(true, 2000))
        assertTrue(policy.observe(true, 3000))
        policy.attempted(3000)
        assertFalse(policy.observe(true, 4000))
        assertFalse(policy.observe(true, 5000))
        assertFalse(policy.observe(true, 7000))
        assertFalse(policy.observe(true, 12_999))
        assertTrue(policy.observe(true, 13_000))
    }
}

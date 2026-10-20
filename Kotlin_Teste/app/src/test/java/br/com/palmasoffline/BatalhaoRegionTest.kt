package br.com.palmasoffline

import org.junit.Assert.*
import org.junit.Test

class BatalhaoRegionTest {
    @Test fun includesOperationalExtremesAndNewAccess() {
        for (point in listOf(
            Coordinates(-10.1752474, -48.886759), // Paraíso
            Coordinates(-9.273159, -49.9576837), // Caseara
            Coordinates(-8.8067391, -49.5560969), // Araguacema
            Coordinates(-10.4255161, -48.8969824), // Pugmil
            Coordinates(-10.119233731352583, -49.18961793050047), // new terminal access
        )) assertTrue(BatalhaoRegion.contains(point))
    }

    @Test fun excludesOldPalmasCoverageAndInvalidCoordinates() {
        assertFalse(BatalhaoRegion.contains(Coordinates(-10.247473, -48.341647)))
        assertFalse(BatalhaoRegion.contains(Coordinates(Double.NaN, -49.0)))
        assertFalse(BatalhaoRegion.contains(Coordinates(-10.0, Double.POSITIVE_INFINITY)))
        assertFalse(BatalhaoRegion.contains(Coordinates(-23.55, -46.63)))
    }
}

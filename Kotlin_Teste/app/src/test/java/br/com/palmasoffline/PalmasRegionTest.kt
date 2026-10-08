package br.com.palmasoffline

import org.junit.Assert.*
import org.junit.Test

class PalmasRegionTest {
    @Test fun includesThePrototypeAndSouthernDistricts() {
        assertTrue(PalmasRegion.contains(Coordinates(-10.247473, -48.341647)))
        assertTrue(PalmasRegion.contains(Coordinates(-10.337, -48.302)))
        assertTrue(PalmasRegion.contains(Coordinates(-10.314, -48.167)))
    }
    @Test fun excludesOtherRegionsAndInvalidCoordinates() {
        assertFalse(PalmasRegion.contains(Coordinates(42.5063, 1.5218)))
        assertFalse(PalmasRegion.contains(Coordinates(-10.7, -48.3)))
        assertFalse(PalmasRegion.contains(Coordinates(Double.NaN, -48.3)))
    }
}

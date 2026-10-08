package br.com.palmasoffline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoordinatesTest {
    @Test fun acceptsBrazilianDecimalCommaAndWhitespace() {
        assertEquals(Coordinates(-10.247473, -48.341647), Coordinates.parse(" -10,247473 ", "-48.341647", "Origem"))
    }
    @Test fun rejectsNonFiniteAndOutOfRangeCoordinates() {
        for (latitude in listOf("NaN", "Infinity", "91", "", "texto")) {
            assertThrows(IllegalArgumentException::class.java) { Coordinates.parse(latitude, "-48", "Origem") }
        }
        for (longitude in listOf("NaN", "-Infinity", "181", "")) {
            assertThrows(IllegalArgumentException::class.java) { Coordinates.parse("-10", longitude, "Destino") }
        }
    }
    @Test fun acceptsBoundaryCoordinates() {
        assertEquals(Coordinates(-90.0, 180.0), Coordinates.parse("-90", "180", "Destino"))
    }
}

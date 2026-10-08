package br.com.palmasoffline

import org.junit.Assert.*
import org.junit.Test

class Polyline6Test {
    @Test fun decodesUsingSixDigitsRatherThanFive() {
        // The standard Google fixture interpreted with Valhalla's six-digit scale.
        val points = Polyline6.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(Coordinates(3.85, -12.02), Coordinates(4.07, -12.095), Coordinates(4.3252, -12.6453)), points)
    }
    @Test fun rejectsTruncatedAndInvalidShapes() {
        for (shape in listOf("_", "?", "!", "___________?")) {
            assertThrows(IllegalArgumentException::class.java) { Polyline6.decode(shape) }
        }
    }
    @Test fun decodesNegativeCoordinatesInPalmas() {
        val expected = listOf(Coordinates(-10.247473, -48.341647), Coordinates(-10.246252, -48.338953))
        // Encode signed deltas to exercise both negative start coordinates and positive movement.
        var lat = 0L
        var lon = 0L
        val encoded = buildString {
            fun appendDelta(delta: Long) {
                var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
                while (value >= 32) { append(((value and 31L).toInt() or 32).plus(63).toChar()); value = value shr 5 }
                append((value + 63).toInt().toChar())
            }
            expected.forEach { point ->
                val newLat = Math.round(point.latitude * 1_000_000)
                val newLon = Math.round(point.longitude * 1_000_000)
                appendDelta(newLat - lat); appendDelta(newLon - lon)
                lat = newLat; lon = newLon
            }
        }
        assertEquals(expected, Polyline6.decode(encoded))
    }
}

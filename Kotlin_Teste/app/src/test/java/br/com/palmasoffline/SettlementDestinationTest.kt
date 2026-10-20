package br.com.palmasoffline

import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class SettlementDestinationTest {
    private fun settlement(status: String? = "nao_revisado"): Feature =
        Feature.fromGeometry(Point.fromLngLat(-49.1896, -10.1192)).apply {
            addStringProperty("nome", "Assentamento de referência")
            status?.let { addStringProperty("status_revisao", it) }
        }

    @Test fun retainsTheReferenceCoordinateAndItsUnverifiedStatus() {
        val selected = SettlementDestination.fromFeature(settlement())!!
        assertEquals(Coordinates(-10.1192, -49.1896), selected.coordinates)
        assertEquals("Assentamento de referência", selected.name)
        assertTrue(selected.reviewMessage.contains("não revisado"))
        val pending = SettlementDestination.fromFeature(settlement("pendente_verificacao"))!!
        assertTrue(pending.reviewMessage.contains("pendente de verificação"))
    }

    @Test fun missingOrUnknownStatusNeverBecomesConfirmation() {
        for (status in listOf(null, "novo_status", "revisado")) {
            val selected = SettlementDestination.fromFeature(settlement(status))!!
            assertTrue(selected.reviewMessage.contains("não informada"))
        }
    }

    @Test fun rejectsKnownIncorrectAndUnusableFeatures() {
        assertNull(SettlementDestination.fromFeature(settlement("incorreto")))
        assertNull(SettlementDestination.fromFeature(Feature.fromGeometry(Point.fromLngLat(-49.1, -10.1))))
        assertNull(SettlementDestination.fromFeature(settlement().apply { addNumberProperty("nome", 123) }))
        assertNull(SettlementDestination.fromFeature(Feature.fromGeometry(LineString.fromLngLats(
            listOf(Point.fromLngLat(-49.1, -10.1), Point.fromLngLat(-49.2, -10.2))
        ))))
        val outside = Feature.fromGeometry(Point.fromLngLat(-48.341647, -10.247473)).apply {
            addStringProperty("nome", "Fora da cobertura")
        }
        assertNull(SettlementDestination.fromFeature(outside))
    }
}

package br.com.palmasoffline

import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/** A settlement reference is not necessarily its road entrance. */
data class SettlementDestination(
    val name: String,
    val coordinates: Coordinates,
    val reviewStatus: String?,
) {
    val reviewMessage: String
        get() = when (reviewStatus) {
            "nao_revisado" -> "Ponto não revisado. Confirme a localização e o acesso."
            "pendente_verificacao" -> "Ponto pendente de verificação. Confirme a localização e o acesso."
            else -> "Validação do ponto não informada. Confirme a localização e o acesso."
        }

    companion object {
        fun fromFeature(feature: Feature): SettlementDestination? {
            val point = feature.geometry() as? Point ?: return null
            val coordinates = Coordinates(point.latitude(), point.longitude())
            if (!BatalhaoRegion.contains(coordinates)) return null
            fun textProperty(key: String): String? = feature.getProperty(key)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim()?.takeIf { it.isNotEmpty() }
            val name = textProperty("nome") ?: return null
            val status = textProperty("status_revisao")
            if (status == "incorreto") return null
            return SettlementDestination(name, coordinates, status)
        }
    }
}

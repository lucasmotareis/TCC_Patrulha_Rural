package br.com.palmasoffline

data class Coordinates(val latitude: Double, val longitude: Double) {
    companion object {
        fun parse(latitude: String, longitude: String, label: String): Coordinates {
            fun number(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()
            val lat = number(latitude)
            val lon = number(longitude)
            require(lat != null && lat.isFinite() && lat in -90.0..90.0) {
                "$label: latitude deve estar entre -90 e 90."
            }
            require(lon != null && lon.isFinite() && lon in -180.0..180.0) {
                "$label: longitude deve estar entre -180 e 180."
            }
            return Coordinates(lat, lon)
        }
    }
}

package br.com.palmasoffline

/** Valhalla shapes use six decimal places; the common five-place decoder is incompatible. */
object Polyline6 {
    fun decode(encoded: String): List<Coordinates> {
        var cursor = 0
        var latitude = 0L
        var longitude = 0L
        fun delta(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(cursor < encoded.length && shift <= 30) { "Geometria de rota incompleta ou inválida." }
                val value = encoded[cursor++].code - 63
                require(value in 0..63) { "Geometria de rota inválida." }
                result = result or ((value and 31).toLong() shl shift)
                if (value < 32) break
                shift += 5
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        return buildList {
            while (cursor < encoded.length) {
                latitude += delta()
                longitude += delta()
                val point = Coordinates(latitude / 1_000_000.0, longitude / 1_000_000.0)
                require(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) {
                    "Geometria de rota fora dos limites geográficos."
                }
                add(point)
            }
        }
    }
}

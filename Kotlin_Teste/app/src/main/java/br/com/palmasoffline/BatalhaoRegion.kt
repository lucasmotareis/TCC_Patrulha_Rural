package br.com.palmasoffline

/** Envelope of the 11 municipalities plus a 15 km buffer, matching package_batalhao.py.
 * This initial bounds check is not proof that a road exists; Valhalla checks the network.
 */
object BatalhaoRegion {
    const val WEST = -50.17020623875758
    const val SOUTH = -10.679515290884172
    const val EAST = -48.45674779141905
    const val NORTH = -8.405369449670118
    fun contains(point: Coordinates) = point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in SOUTH..NORTH && point.longitude in WEST..EAST
}

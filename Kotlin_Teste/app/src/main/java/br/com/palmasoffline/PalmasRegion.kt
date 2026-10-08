package br.com.palmasoffline

/** Visual extract extent, matching scripts/prepare_palmas.py. Not a municipal boundary. */
object PalmasRegion {
    const val WEST = -48.45
    const val SOUTH = -10.42
    const val EAST = -48.05
    const val NORTH = -10.08
    fun contains(point: Coordinates) = point.latitude in SOUTH..NORTH && point.longitude in WEST..EAST
}

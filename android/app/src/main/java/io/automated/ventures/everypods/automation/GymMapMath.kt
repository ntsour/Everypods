package io.automated.ventures.everypods.automation

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.PI

/** Pure helpers for the Gym zone map picker (Web Mercator / OSM tiles). */
object GymMapMath {
    /** Web Mercator cannot show the poles; OSM tiles stop at ±85.05112878°. */
    const val MAX_LAT = 85.05112878
    const val MIN_ZOOM = 3.0
    const val MAX_ZOOM = 19.0
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    private const val TILE_PX = 256.0

    fun clampLatitude(lat: Double) = lat.coerceIn(-MAX_LAT, MAX_LAT)

    /** Wraps any longitude into [-180, 180). */
    fun wrapLongitude(lon: Double): Double {
        val w = lon - 360.0 * floor((lon + 180.0) / 360.0)
        return if (w >= 180.0) w - 360.0 else w
    }

    /** Rounds a picked point to 6 decimals (~0.1 m), the precision stored for the zone. */
    fun normalize(lat: Double, lon: Double): Pair<Double, Double> {
        fun r(v: Double) = Math.round(v * 1e6) / 1e6
        return r(clampLatitude(lat)) to r(wrapLongitude(lon))
    }

    /**
     * Zoom level at which a circle of [radiusMeters] (plus margin) fits in the smaller
     * viewport side of [viewportPx] device pixels (osmdroid scales 256 px tiles by density).
     */
    fun zoomForRadius(radiusMeters: Int, latitude: Double, viewportPx: Int, density: Float = 1f): Double {
        if (radiusMeters <= 0 || viewportPx <= 0) return MAX_ZOOM
        val tile = TILE_PX * density.coerceAtLeast(0.5f)
        val metersAcross = radiusMeters * 2.0 * 1.4 // ring + 40% margin
        val metersPerPxAtZ0 = EARTH_CIRCUMFERENCE_M * cos(clampLatitude(latitude) * PI / 180.0) / tile
        val z = ln(metersPerPxAtZ0 * viewportPx / metersAcross) / ln(2.0)
        return floor(z.coerceIn(MIN_ZOOM, MAX_ZOOM) * 10) / 10.0
    }
}

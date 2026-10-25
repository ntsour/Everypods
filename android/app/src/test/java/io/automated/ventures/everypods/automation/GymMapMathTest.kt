package io.automated.ventures.everypods.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GymMapMathTest {
    @Test fun clampsLatitudeToMercator() {
        assertEquals(GymMapMath.MAX_LAT, GymMapMath.clampLatitude(90.0), 0.0)
        assertEquals(-GymMapMath.MAX_LAT, GymMapMath.clampLatitude(-91.0), 0.0)
        assertEquals(40.4, GymMapMath.clampLatitude(40.4), 0.0)
    }
    @Test fun wrapsLongitude() {
        assertEquals(-170.0, GymMapMath.wrapLongitude(190.0), 1e-9)
        assertEquals(170.0, GymMapMath.wrapLongitude(-190.0), 1e-9)
        assertEquals(-180.0, GymMapMath.wrapLongitude(180.0), 1e-9)
        assertEquals(3.7, GymMapMath.wrapLongitude(3.7 + 720), 1e-9)
    }
    @Test fun normalizeRoundsToSixDecimals() {
        assertEquals(40.416775 to -3.70379, GymMapMath.normalize(40.41677512, -3.70379049))
    }
    @Test fun largerRadiusZoomsOut() {
        val z150 = GymMapMath.zoomForRadius(150, 40.4, 1000, 2.75f)
        val z1000 = GymMapMath.zoomForRadius(1000, 40.4, 1000, 2.75f)
        assertTrue(z1000 < z150)
        assertTrue(z150 in 14.0..18.0)
    }
    @Test fun zoomIsClamped() {
        assertEquals(GymMapMath.MAX_ZOOM, GymMapMath.zoomForRadius(1, 0.0, 4000, 3f), 0.0)
        assertEquals(GymMapMath.MIN_ZOOM, GymMapMath.zoomForRadius(5_000_000, 0.0, 100, 1f), 0.0)
        assertEquals(GymMapMath.MAX_ZOOM, GymMapMath.zoomForRadius(0, 0.0, 100), 0.0)
    }
}

package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeoTest {

    @Test
    fun geohashMatchesKnownValue() {
        // Wikipedia の例（57.64911, 10.40744 → u4pruydqqvj）
        assertEquals("u4pruydqqvj", Geo.geohash(57.64911, 10.40744, 11))
        assertEquals("u4pruy", Geo.geohash(57.64911, 10.40744))
    }

    @Test
    fun nearbyPointsShareCellAndFarPointsDoNot() {
        val a = Geo.geohash(35.681236, 139.767125)
        assertEquals(6, a.length)
        assertEquals(a, Geo.geohash(35.681300, 139.767200)) // 10m ほど離れた点
        assertNotEquals(a, Geo.geohash(35.700000, 139.767125)) // 2km 北
    }

    @Test
    fun distanceBetweenTokyoAndOsakaStations() {
        val d = Geo.distanceM(35.681236, 139.767125, 34.702485, 135.495951)
        assertTrue(d in 400_000.0..406_000.0, "距離: $d")
    }
}

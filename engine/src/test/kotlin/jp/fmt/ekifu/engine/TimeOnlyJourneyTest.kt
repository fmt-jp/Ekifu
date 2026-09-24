package jp.fmt.ekifu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeOnlyJourneyTest {
    // 経度方向に 1km, 1km, 2km の間隔（赤道上なので経度差がそのまま距離に比例）
    private val kmPerDegree = 111.195
    private fun station(name: String, km: Double, underground: Boolean = false) =
        Station(name, 0.0, km / kmPerDegree, underground)

    private val route = Route(
        name = "テスト",
        expectedMinutes = 20.0,
        stations = listOf(
            station("A", 0.0),
            station("B", 1.0, underground = true),
            station("C", 2.0, underground = true),
            station("D", 4.0),
        ),
    )
    private val journey = TimeOnlyJourney(route)

    @Test
    fun stationTimesAreProportionalToDistance() {
        assertEquals(listOf(0.0, 0.25, 0.5, 1.0), journey.stationProgress.map { Math.round(it * 1000) / 1000.0 })
    }

    @Test
    fun undergroundSegmentsAreMerged() {
        assertEquals(1, journey.undergroundRanges.size)
        assertEquals(0.25, journey.undergroundRanges[0].start, 1e-3)
        assertEquals(1.0, journey.undergroundRanges[0].endInclusive, 1e-3)
    }

    @Test
    fun progressesWithElapsedTimeOnly() {
        val start = journey.snapshot(0.0)
        assertEquals(0, start.lastPassedStationIndex)
        assertFalse(start.underground)

        val quarter = journey.snapshot(20 * 60 * 0.3)
        assertEquals(1, quarter.lastPassedStationIndex)
        assertTrue(quarter.underground)
        assertEquals(20 * 60 * 0.7, quarter.remainingSeconds, 1e-6)

        val end = journey.snapshot(20 * 60.0)
        assertEquals(3, end.lastPassedStationIndex)
        assertEquals(1.0, end.progress, 1e-9)
        assertFalse(end.underground)
    }

    @Test
    fun samePlaceStationsAreSpacedEvenly() {
        val flat = TimeOnlyJourney(route.copy(stations = route.stations.map { it.copy(lat = 1.0, lng = 1.0) }))
        assertEquals(listOf(0.0, 1.0 / 3, 2.0 / 3, 1.0), flat.stationProgress)
    }
}

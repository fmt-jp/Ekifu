package jp.fmt.ekifu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoJourneyTest {
    private val demo = DemoJourney.create()

    @Test
    fun eighteenMinutesArePlayedInThree() {
        assertEquals(0.0, demo.snapshot(0.0).progress, 1e-9)
        assertEquals(0.5, demo.snapshot(90.0).progress, 1e-9)
        assertEquals(1.0, demo.snapshot(180.0).progress, 1e-9)
    }

    @Test
    fun stationsArePassedOnSchedule() {
        // 3 分ごとの駅 → 再生時間では 30 秒ごと
        assertEquals(0, demo.snapshot(0.0).lastPassedStationIndex)
        assertEquals(0, demo.snapshot(29.9).lastPassedStationIndex)
        assertEquals(1, demo.snapshot(30.0).lastPassedStationIndex)
        assertEquals(6, demo.snapshot(180.0).lastPassedStationIndex)
    }

    @Test
    fun undergroundBetween27And72Percent() {
        assertFalse(demo.snapshot(180 * 0.26).underground)
        assertTrue(demo.snapshot(180 * 0.28).underground)
        assertTrue(demo.snapshot(180 * 0.71).underground)
        assertFalse(demo.snapshot(180 * 0.73).underground)
    }

    @Test
    fun longDemoTakesThirtyMinutes() {
        val long = DemoJourney.create(DemoJourney.LONG_PLAYBACK_MINUTES)
        assertEquals(0.5, long.snapshot(15 * 60.0).progress, 1e-9)
        assertEquals(1, long.snapshot(5 * 60.0).lastPassedStationIndex)
        assertEquals(1.0, long.snapshot(30 * 60.0).progress, 1e-9)
    }
}

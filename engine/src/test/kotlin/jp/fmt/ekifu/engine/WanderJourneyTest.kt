package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WanderJourneyTest {
    private val metersPerDegreeLat = 111_320.0
    private val minute = 60_000L

    /** 東京駅付近から北へ [metersNorth] 進んだ位置。 */
    private fun at(minutes: Long, metersNorth: Double, accuracy: Double = 20.0) =
        LocationSample(minutes * minute, 35.681 + metersNorth / metersPerDegreeLat, 139.767, accuracy)

    @Test
    fun classifiesSpeed() {
        assertEquals(Motion.STILL, WanderJourney.classify(0.2))
        assertEquals(Motion.WALK, WanderJourney.classify(1.3))
        assertEquals(Motion.RIDE, WanderJourney.classify(12.0))
    }

    @Test
    fun motionFollowsDistanceBetweenSamplesFiveMinutesApart() {
        val journey = WanderJourney { 12.0 }
        journey.onLocation(at(0, 0.0))
        assertEquals(Motion.STILL, journey.motion)
        // 5 分で 400m（約 1.3m/s）→ 歩き
        assertTrue(journey.onLocation(at(5, 400.0)))
        assertEquals(Motion.WALK, journey.motion)
        // 5 分で 5km → 乗り物
        journey.onLocation(at(10, 5400.0))
        assertEquals(Motion.RIDE, journey.motion)
        // 5 分でほとんど動かない → 止まっている
        journey.onLocation(at(15, 5430.0))
        assertEquals(Motion.STILL, journey.motion)
    }

    @Test
    fun gpsNoiseWithinAccuracyIsNotMovement() {
        val journey = WanderJourney { 12.0 }
        journey.onLocation(at(0, 0.0, accuracy = 100.0))
        journey.onLocation(at(5, 180.0, accuracy = 100.0))
        assertEquals(Motion.STILL, journey.motion)
    }

    @Test
    fun samplesTooCloseInTimeDoNotChangeMotion() {
        val journey = WanderJourney { 12.0 }
        journey.onLocation(at(0, 0.0))
        journey.onLocation(LocationSample(10_000, 35.681 + 300 / metersPerDegreeLat, 139.767, 20.0))
        assertEquals(Motion.STILL, journey.motion)
    }

    @Test
    fun newPlaceRingsAndSamePlaceKeepsItsMotif() {
        val journey = WanderJourney { 12.0 }
        assertNull(journey.snapshot(0.0).landmark)

        assertTrue(journey.onLocation(at(0, 0.0)))
        val first = journey.snapshot(0.0).landmark!!
        assertEquals(0, first.sequence)

        // 同じ区画の中で少し動いただけ → 場所は変わらない
        assertFalse(journey.onLocation(at(5, 30.0)))
        assertEquals(first, journey.snapshot(0.0).landmark)

        // 別の区画へ → 新しい場所
        journey.onLocation(at(10, 3000.0))
        val second = journey.snapshot(0.0).landmark!!
        assertEquals(1, second.sequence)
        assertNotEquals(first.motifKey, second.motifKey)

        // 元の区画に戻る → 番号は進むが、モチーフは最初と同じ
        journey.onLocation(at(15, 0.0))
        val back = journey.snapshot(0.0).landmark!!
        assertEquals(2, back.sequence)
        assertEquals(first.motifKey, back.motifKey)
    }

    @Test
    fun placeCellsAreAboutFiveHundredMeters() {
        val a = PlaceCell.of(35.681, 139.767)
        assertEquals(a, PlaceCell.of(a.centerLat, a.centerLng))
        val north = PlaceCell.of(a.centerLat + C.PLACE_CELL_METERS / metersPerDegreeLat, a.centerLng)
        assertEquals(a.row + 1, north.row)
    }

    @Test
    fun motionSelectsPhase() {
        val journey = WanderJourney { 12.0 }
        journey.onLocation(at(0, 0.0))
        assertEquals(Phase.DEPARTURE, journey.snapshot(0.0).phase)
        journey.onLocation(at(5, 5000.0))
        assertEquals(Phase.MIDDLE, journey.snapshot(0.0).phase)
    }

    @Test
    fun nightIsDarkerThanDay() {
        assertEquals(0.0, WanderJourney.darknessAt(12.0), 1e-9)
        assertEquals(0.55, WanderJourney.darknessAt(0.0), 1e-9)
        assertEquals(0.55, WanderJourney.darknessAt(24.0), 1e-9)
        assertTrue(WanderJourney.darknessAt(21.0) > WanderJourney.darknessAt(17.0))
        assertEquals(0.55, WanderJourney { 23.5 }.snapshot(0.0).darkness, 0.03)
    }
}

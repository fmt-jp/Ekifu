package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 設定で変えられる位置の取り方 */
class LocationPolicyTest {

    @Test
    fun standardKeepsTheFormerBehaviour() {
        val p = LocationPolicy.STANDARD
        assertEquals(300.0, SceneTiming.locationIntervalSec(nearPlace = false, speed = Speed.WALK, policy = p))
        assertEquals(60.0, SceneTiming.locationIntervalSec(nearPlace = false, speed = Speed.VEHICLE, policy = p))
        assertEquals(15.0, SceneTiming.locationIntervalSec(nearPlace = true, speed = Speed.WALK, policy = p))
        assertEquals(60.0, SceneTiming.locationIntervalSec(nearPlace = true, speed = Speed.WALK, arrived = true, policy = p))
        assertEquals(1500.0, SceneTiming.nearDistanceM(Speed.WALK, p))
        assertEquals(3000.0, SceneTiming.nearDistanceM(Speed.VEHICLE, p))
        assertTrue(SceneTiming.highAccuracy(nearPlace = true, policy = p))
        assertFalse(SceneTiming.highAccuracy(nearPlace = false, policy = p))
        assertFalse(SceneTiming.highAccuracy(nearPlace = true, arrived = true, policy = p))
        assertEquals(300.0, SceneTiming.checkIntervalSec(Speed.WALK, p))
        assertEquals(60.0, SceneTiming.checkIntervalSec(Speed.VEHICLE, p))
    }

    @Test
    fun gpsModes() {
        val off = LocationPolicy(gpsMode = GpsMode.OFF)
        assertFalse(SceneTiming.highAccuracy(nearPlace = true, policy = off))
        val always = LocationPolicy(gpsMode = GpsMode.ALWAYS)
        assertTrue(SceneTiming.highAccuracy(nearPlace = false, policy = always))
        // 到着後は、GPS を続ける設定でなければ省電力に戻す
        assertFalse(SceneTiming.highAccuracy(nearPlace = true, arrived = true, policy = always))
        assertTrue(SceneTiming.highAccuracy(nearPlace = true, arrived = true, policy = always.copy(keepGpsAfterArrival = true)))
    }

    @Test
    fun keepingGpsAfterArrivalKeepsTheNearInterval() {
        val p = LocationPolicy(nearIntervalSec = 10.0, keepGpsAfterArrival = true)
        assertEquals(10.0, SceneTiming.locationIntervalSec(nearPlace = true, speed = Speed.WALK, arrived = true, policy = p))
        assertTrue(SceneTiming.highAccuracy(nearPlace = true, arrived = true, policy = p))
    }

    @Test
    fun scenesFollowAShorterNormalInterval() {
        // ふだんの間隔を1分にすると、場面も1分ごとに確かめる（切り替えはマスか速さが変わったときだけ）
        val p = LocationPolicy(normalIntervalSec = 60.0)
        assertEquals(60.0, SceneTiming.checkIntervalSec(Speed.WALK, p))
        assertEquals(60.0, SceneTiming.locationIntervalSec(nearPlace = false, speed = Speed.STILL, policy = p))
        val a = Scene("xn7e2t", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY)
        assertFalse(SceneTiming.shouldSwitch(a, a, 60.0))
        assertTrue(SceneTiming.shouldSwitch(a, a.copy(gridId = "xn7e2v"), 60.0))
    }

    @Test
    fun presetsAreOrderedFromBatteryToAccuracy() {
        val b = LocationPreset.BATTERY.policy!!
        val s = LocationPreset.STANDARD.policy!!
        val a = LocationPreset.ACCURACY.policy!!
        assertTrue(b.nearDistanceM < s.nearDistanceM && s.nearDistanceM < a.nearDistanceM)
        assertTrue(b.nearIntervalSec > s.nearIntervalSec && s.nearIntervalSec > a.nearIntervalSec)
        assertTrue(b.vehicleIntervalSec > s.vehicleIntervalSec && s.vehicleIntervalSec > a.vehicleIntervalSec)
        assertEquals(LocationPolicy(), s)
        assertEquals(null, LocationPreset.CUSTOM.policy)
        // 選択肢にはプリセットの値が含まれる
        for (p in listOf(b, s, a)) {
            assertTrue(p.nearDistanceM in LocationPolicy.NEAR_DISTANCE_CHOICES_M)
            assertTrue(p.normalIntervalSec in LocationPolicy.NORMAL_INTERVAL_CHOICES_SEC)
            assertTrue(p.vehicleIntervalSec in LocationPolicy.VEHICLE_INTERVAL_CHOICES_SEC)
            assertTrue(p.nearIntervalSec in LocationPolicy.NEAR_INTERVAL_CHOICES_SEC)
        }
    }
}

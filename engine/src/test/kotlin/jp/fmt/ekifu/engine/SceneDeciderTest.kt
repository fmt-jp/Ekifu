package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneDeciderTest {

    private val t0 = 1_718_942_400_000L // 2024-06-21 13:00 JST（昼）
    private val min5 = 5 * 60_000L
    private val noVisits: (String) -> Int = { 1 }

    /** 北へ distanceM 進んだ位置 */
    private fun fix(northM: Double, timeMs: Long) =
        LocationFix(35.6581 + northM / 111_000.0, 139.7414, timeMs)

    @Test
    fun firstFixIsTreatedAsWalking() {
        val d = SceneDecider().decide(fix(0.0, t0), t0, noVisits)!!
        assertEquals(Speed.WALK, d.scene.speed)
        assertNull(d.speedKmh)
    }

    @Test
    fun speedIsClassifiedFromDistanceOverTime() {
        val sd = SceneDecider()
        sd.decide(fix(0.0, t0), t0, noVisits)
        // 5分で 20m → 0.24 km/h：静止
        assertEquals(Speed.STILL, sd.decide(fix(20.0, t0 + min5), t0 + min5, noVisits)!!.scene.speed)
        // 5分で 400m → 4.8 km/h：徒歩
        assertEquals(Speed.WALK, sd.decide(fix(420.0, t0 + 2 * min5), t0 + 2 * min5, noVisits)!!.scene.speed)
        // 5分で 3km → 36 km/h：乗り物
        val v = sd.decide(fix(3420.0, t0 + 3 * min5), t0 + 3 * min5, noVisits)!!
        assertEquals(Speed.VEHICLE, v.scene.speed)
        assertEquals(36.0, v.speedKmh!!, 0.5)
    }

    @Test
    fun missingFixKeepsPreviousSceneAndSpeed() {
        val sd = SceneDecider()
        assertNull(sd.decide(null, t0, noVisits), "一度も位置が取れていなければ決めない")
        sd.decide(fix(0.0, t0), t0, noVisits)
        val moving = sd.decide(fix(3000.0, t0 + min5), t0 + min5, noVisits)!!
        val lost = sd.decide(null, t0 + 2 * min5, noVisits)!!
        assertFalse(lost.located)
        assertEquals(moving.scene.gridId, lost.scene.gridId)
        assertEquals(Speed.VEHICLE, lost.scene.speed)
        assertEquals(moving.speedKmh, lost.speedKmh)
        // 地下から出たら、そこからの距離で速さを求め直す
        val back = sd.decide(fix(3100.0, t0 + 3 * min5), t0 + 3 * min5, noVisits)!!
        assertTrue(back.located)
        assertEquals(Speed.WALK, back.scene.speed)
    }

    @Test
    fun sunIsUpdatedEvenWithoutFix() {
        val sd = SceneDecider()
        sd.decide(fix(0.0, t0), t0, noVisits)
        val night = t0 + 10 * 3_600_000L // 23:00 JST
        assertEquals(SunLevel.NIGHT, sd.decide(null, night, noVisits)!!.scene.sun)
    }

    @Test
    fun familiarityFromVisitDays() {
        assertEquals(Familiarity.NEW, SceneDecider.familiarityOf(1))
        assertEquals(Familiarity.NORMAL, SceneDecider.familiarityOf(2))
        assertEquals(Familiarity.NORMAL, SceneDecider.familiarityOf(9))
        assertEquals(Familiarity.FAMILIAR, SceneDecider.familiarityOf(10))
        val d = SceneDecider().decide(fix(0.0, t0), t0) { 12 }!!
        assertEquals(Familiarity.FAMILIAR, d.scene.familiarity)
    }

    @Test
    fun returningToSamePlaceGivesSameMotif() {
        val sd = SceneDecider()
        val home = sd.decide(fix(0.0, t0), t0, noVisits)!!.scene
        sd.decide(fix(3000.0, t0 + min5), t0 + min5, noVisits)
        val again = sd.decide(fix(5.0, t0 + 6 * min5), t0 + 6 * min5, noVisits)!!.scene
        assertEquals(home.gridId, again.gridId)
        assertEquals(Motifs.placeMotif(home.gridId), Motifs.placeMotif(again.gridId))
    }

    @Test
    fun withoutLocationMovesToNewPseudoPlaceEachScene() {
        val jst = 9 * 3_600_000
        val a = SceneDecider.withoutLocation(42L, 0, t0, jst)
        val b = SceneDecider.withoutLocation(42L, 1, t0 + min5, jst)
        assertNotEquals(a.gridId, b.gridId)
        assertEquals(Speed.WALK, a.speed)
        assertEquals(Familiarity.NORMAL, a.familiarity)
        assertEquals(SunLevel.DAY, a.sun)
        assertEquals(SunLevel.NIGHT, SceneDecider.withoutLocation(42L, 2, t0 + 10 * 3_600_000L, jst).sun)
    }
}

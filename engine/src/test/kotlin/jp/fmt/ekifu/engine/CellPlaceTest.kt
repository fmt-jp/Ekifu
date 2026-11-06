package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 基地局で登録地点を見分ける（地下の駅）と「着いた」ボタン */
class CellPlaceTest {

    private val lat0 = 35.0
    private val lng0 = 139.0
    private val stationA = RegisteredPlace("a", "A駅", lat0, lng0, 500, 100, Mood.BRIGHT, 1)
    private val stationB = RegisteredPlace("b", "B駅", lat0 + 2000 / 111_195.0, lng0, 500, 100, Mood.CALM, 2)
    private val min = 60_000L

    private fun at(northM: Double, tMin: Double) = LocationFix(lat0 + northM / 111_195.0, lng0, (tMin * min).toLong())

    @Test
    fun cellBookPicksThePlaceSeenMostOften() {
        val book = CellBook()
        book.learn("a", listOf("LTE:1", "LTE:2"))
        book.learn("a", listOf("LTE:1"))
        book.learn("b", listOf("LTE:2", "LTE:3"))
        assertEquals("a", book.placeFor(setOf("LTE:1")))
        assertEquals("a", book.placeFor(setOf("LTE:2")), "同じ基地局を覚えた地点が複数なら回数の多いほう")
        assertEquals("b", book.placeFor(setOf("LTE:3")))
        assertNull(book.placeFor(setOf("LTE:9")))
        assertEquals(2, book.count("a"))
        assertNull(book.placeFor(setOf("LTE:3"), setOf("a")), "消した地点は選ばない")
        book.forget("b")
        assertNull(book.placeFor(setOf("LTE:3")))
    }

    @Test
    fun undergroundArrivalByCellThenLeaveWhenItDisappears() {
        val t = PlaceTracker(listOf(stationA, stationB))
        t.update(at(5000.0, 0.0)) // 地上で一度位置が取れたあと地下へ
        val arrive = t.updateByCell("a", 10 * min)
        assertTrue(arrive.single() is PlaceEvent.Arrive)
        assertEquals(PlaceTracker.State.ARRIVED, t.state)
        // 停車中に基地局が一瞬見えなくても、90秒までは到着のまま
        assertTrue(t.updateByCell(null, 10 * min + 60_000).isEmpty())
        assertTrue(t.updateByCell("a", 10 * min + 75_000).isEmpty())
        // 発車して見えなくなって90秒たったら離れる
        val leave = t.updateByCell(null, 10 * min + 75_000 + 91_000)
        assertTrue(leave.single() is PlaceEvent.Leave)
        assertEquals(PlaceTracker.State.NONE, t.state)
        // 次の駅
        val next = t.updateByCell("b", 14 * min)
        assertTrue(next.single() is PlaceEvent.Arrive)
        assertEquals("b", t.current?.id)
    }

    @Test
    fun goingStraightToTheNextStationLeavesQuietlyAndArrives() {
        val t = PlaceTracker(listOf(stationA, stationB))
        t.update(at(5000.0, 0.0))
        t.updateByCell("a", 10 * min)
        val e = t.updateByCell("b", 12 * min)
        assertEquals(listOf(PlaceEvent.Leave(stationA), PlaceEvent.Arrive(stationB)), e)
    }

    @Test
    fun startingInsideByCellStaysWithoutArrival() {
        val t = PlaceTracker(listOf(stationA))
        assertTrue(t.updateByCell("a", 0L).single() is PlaceEvent.StayAtStart)
    }

    @Test
    fun aFixAfterCellArrivalUsesDistanceAgain() {
        val t = PlaceTracker(listOf(stationA))
        t.update(at(5000.0, 0.0))
        t.updateByCell("a", 10 * min)
        // 地上に出て位置が届いたら、距離で判定（内側の円の中なら到着のまま）
        assertTrue(t.update(at(50.0, 11.0)).isEmpty())
        // 位置が届いているあいだは、基地局が見えなくても離れない
        assertTrue(t.updateByCell(null, 20 * min).isEmpty())
        assertEquals(PlaceTracker.State.ARRIVED, t.state)
        assertTrue(t.update(at(400.0, 21.0)).single() is PlaceEvent.Leave)
    }

    @Test
    fun manualArrival() {
        val t = PlaceTracker(listOf(stationA, stationB))
        val fix = at(1800.0, 0.0)
        // 基地局で分かればその地点、なければ最後の位置から3km以内で一番近い地点
        assertEquals("a", t.manualTarget("a", fix)?.id)
        assertEquals("b", t.manualTarget(null, fix)?.id)
        assertNull(t.manualTarget(null, at(10_000.0, 0.0)))
        assertNull(t.manualTarget(null, null))
        assertTrue(t.forceArrive("b", 1 * min).single() is PlaceEvent.Arrive)
        assertTrue(t.forceArrive("b", 2 * min).single() is PlaceEvent.Arrive, "すでに到着していても押したら鳴らし直す")
        assertTrue(t.forceArrive("b", 2 * min + 30_000).single() is PlaceEvent.Arrive, "30分以内でも演出なしにしない")
        // 基地局が見えなくなって90秒で離れる
        assertTrue(t.updateByCell(null, 5 * min).single() is PlaceEvent.Leave)
    }

    private fun coarseAt(northM: Double, tMin: Double, acc: Double = 800.0) =
        LocationFix(lat0 + northM / 111_195.0, lng0, (tMin * min).toLong(), accuracyM = acc)

    @Test
    fun coarseFixesDoNotUndoACellOrManualArrival() {
        val t = PlaceTracker(listOf(stationA, stationB))
        t.update(at(5000.0, 0.0))
        t.forceArrive("a", 10 * min)
        // 地下で基地局から出した粗い位置（数百mずれる）が届いても離れない
        assertTrue(t.update(coarseAt(1500.0, 10.2)).isEmpty())
        assertTrue(t.update(coarseAt(2000.0, 10.5, acc = 1500.0)).isEmpty())
        assertEquals("a", t.current?.id)
        assertTrue(t.arrivedByCell)
        // 位置が届いていても、基地局で到着したあいだは基地局が見えなくなって90秒で離れる
        assertTrue(t.updateByCell(null, 11 * min, locationStale = false).isEmpty())
        assertTrue(t.updateByCell(null, 12 * min, locationStale = false).single() is PlaceEvent.Leave)
    }

    @Test
    fun aPreciseFixAfterCellArrivalCanLeave() {
        val t = PlaceTracker(listOf(stationA))
        t.update(at(5000.0, 0.0))
        t.updateByCell("a", 10 * min)
        assertTrue(t.update(coarseAt(400.0, 10.5, acc = 20.0)).single() is PlaceEvent.Leave)
    }

    @Test
    fun cellsArriveOnlyWhileTheLocationIsUnusable() {
        val t = PlaceTracker(listOf(stationA, stationB))
        t.update(at(5000.0, 0.0))
        assertTrue(t.updateByCell("a", 1 * min, locationStale = false).isEmpty())
        assertTrue(t.updateByCell("a", 2 * min, locationStale = true).single() is PlaceEvent.Arrive)
        // 基地局で到着しているあいだは、位置が届いていても次の駅の基地局で移る
        assertEquals(listOf(PlaceEvent.Leave(stationA), PlaceEvent.Arrive(stationB)), t.updateByCell("b", 4 * min, locationStale = false))
    }

    @Test
    fun onlyCoarseFixesWhileGpsIsRequestedCountAsNoLocation() {
        val now = 100 * min
        // 粗い位置は15秒ごとに届いているが、精度のよい位置は2分前が最後。GPS は5分前から頼んでいる
        assertTrue(SceneTiming.locationStale(now - 15_000, now, 15.0, lastPreciseFixMs = now - 2 * min, gpsSinceMs = now - 5 * min))
        assertTrue(SceneTiming.locationStale(now - 15_000, now, 15.0, lastPreciseFixMs = null, gpsSinceMs = now - 50_000))
        // GPS を頼んでまだ45秒たっていなければ待つ
        assertFalse(SceneTiming.locationStale(now - 15_000, now, 15.0, lastPreciseFixMs = null, gpsSinceMs = now - 30_000))
        // 精度のよい位置が届いていれば取れている
        assertFalse(SceneTiming.locationStale(now - 15_000, now, 15.0, lastPreciseFixMs = now - 15_000, gpsSinceMs = now - 5 * min))
        // GPS を頼んでいなければ、粗い位置でも取れている
        assertFalse(SceneTiming.locationStale(now - 15_000, now, 60.0, lastPreciseFixMs = null, gpsSinceMs = null))
        assertTrue(SceneTiming.coarse(coarseAt(0.0, 0.0, acc = 151.0)))
        assertFalse(SceneTiming.coarse(coarseAt(0.0, 0.0, acc = 30.0)))
    }

    @Test
    fun locationIsStaleOnlyAfterTwiceTheInterval() {
        val now = 100 * min
        assertTrue(SceneTiming.locationStale(null, now, 15.0))
        assertFalse(SceneTiming.locationStale(now - 30_000, now, 15.0))
        assertTrue(SceneTiming.locationStale(now - 46_000, now, 15.0), "GPS 15秒ごとなら45秒届かなければ取れていない")
        assertFalse(SceneTiming.locationStale(now - 9 * min, now, 300.0), "5分ごとなら10分までは取れている")
        assertTrue(SceneTiming.locationStale(now - 11 * min, now, 300.0))
    }
}

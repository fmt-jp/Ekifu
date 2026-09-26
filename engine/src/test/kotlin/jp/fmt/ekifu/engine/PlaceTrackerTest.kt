package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.MusicConstants as C

class PlaceTrackerTest {

    private val lat0 = 35.0
    private val lng0 = 139.0
    private val park = RegisteredPlace("park", "いつもの公園", lat0, lng0, 500, 100, Mood.CALM, 12345)
    private val min = 60_000L

    /** 公園から北へ northM メートル、t 分の位置 */
    private fun at(northM: Double, tMin: Double, eastM: Double = 0.0) = LocationFix(
        lat0 + northM / 111_195.0,
        lng0 + eastM / (111_195.0 * kotlin.math.cos(Math.toRadians(lat0))),
        (tMin * min).toLong(),
    )

    @Test
    fun walkingInApproachesWithinTwoMinutesThenArrives() {
        val t = PlaceTracker(listOf(park))
        var enteredOuterAt: Double? = null
        var approachAt: Double? = null
        var arriveAt: Double? = null
        // 1分ごとの位置、時速4.8km（80m/分）で公園へ歩く
        for (i in 0..25) {
            val d = 1800.0 - 80.0 * i
            if (d < 0) break
            if (d <= 500 && enteredOuterAt == null) enteredOuterAt = i.toDouble()
            for (e in t.update(at(d, i.toDouble()))) {
                when (e) {
                    is PlaceEvent.Approach -> approachAt = i.toDouble()
                    is PlaceEvent.Arrive -> arriveAt = i.toDouble()
                    else -> error("想定外: $e")
                }
            }
        }
        assertTrue(approachAt!! - enteredOuterAt!! <= 2.0)
        assertTrue(arriveAt!! > approachAt!!)
        assertEquals(PlaceTracker.State.ARRIVED, t.state)
    }

    @Test
    fun leavingIsJudgedOnlyBeyond1_2TimesRadius() {
        val t = PlaceTracker(listOf(park))
        t.update(at(2000.0, 0.0))
        assertTrue(t.update(at(50.0, 1.0)).single() is PlaceEvent.Arrive)
        assertTrue(t.update(at(550.0, 2.0)).isEmpty(), "外側の円を出ても 1.2倍（600m）までは滞在のまま")
        assertTrue(t.update(at(590.0, 3.0)).isEmpty())
        val leave = t.update(at(610.0, 4.0))
        assertTrue(leave.single() is PlaceEvent.Leave)
        assertEquals(PlaceTracker.State.NONE, t.state)
    }

    @Test
    fun boundaryFlappingDoesNotRepeatApproachWithin30Minutes() {
        val t = PlaceTracker(listOf(park))
        t.update(at(2000.0, 0.0))
        assertTrue(t.update(at(480.0, 1.0)).single() is PlaceEvent.Approach)
        assertTrue(t.update(at(620.0, 2.0)).single() is PlaceEvent.Leave)
        // 境目を行き来しても30分以内は接近も離脱も鳴らさない
        var tMin = 3.0
        repeat(10) {
            assertTrue(t.update(at(480.0, tMin)).isEmpty(), "$tMin 分：接近を繰り返した")
            tMin += 1
            assertTrue(t.update(at(620.0, tMin)).isEmpty(), "$tMin 分：離脱を繰り返した")
            tMin += 1
        }
        // 最後に離れてから30分たてば、また接近の演出
        assertTrue(t.update(at(480.0, tMin + 31)).single() is PlaceEvent.Approach)
    }

    @Test
    fun arrivalStillPlaysDuringSuppressedApproach() {
        val t = PlaceTracker(listOf(park))
        t.update(at(2000.0, 0.0))
        t.update(at(480.0, 1.0))
        t.update(at(700.0, 2.0))
        assertTrue(t.update(at(450.0, 5.0)).isEmpty())
        assertTrue(t.update(at(60.0, 8.0)).single() is PlaceEvent.Arrive)
    }

    @Test
    fun carryOverForStyleSwitch() {
        val t = PlaceTracker(listOf(park))
        t.update(at(2000.0, 0.0))
        assertEquals(CarryOver(DAY_WALK), t.carryOver(DAY_WALK))
        t.update(at(480.0, 1.0))
        assertEquals(CarryOver(DAY_WALK, approaching = park.place), t.carryOver(DAY_WALK))
        t.update(at(60.0, 2.0))
        assertEquals(CarryOver(DAY_WALK, stayAt = park.place), t.carryOver(DAY_WALK))
        t.update(at(700.0, 3.0))
        assertEquals(CarryOver(null), t.carryOver(null))
        // 音を変えずに追いかけている再接近は、接近を引き継がない
        t.update(at(450.0, 5.0))
        assertEquals(PlaceTracker.State.APPROACHING, t.state)
        assertEquals(CarryOver(DAY_WALK), t.carryOver(DAY_WALK))
    }

    @Test
    fun innerCircleWinsThenNearestPlace() {
        val cafe = RegisteredPlace("cafe", "喫茶店", lat0 + 400 / 111_195.0, lng0, 800, 80, Mood.NOSTALGIC, 1)
        val station = RegisteredPlace("station", "駅", lat0 + 700 / 111_195.0, lng0, 1000, 100, Mood.BRIGHT, 2)
        // 250m 北：公園（250m）・喫茶店（150m）・駅（450m）の外側の円に入っている：一番近い喫茶店
        val t1 = PlaceTracker(listOf(park, cafe, station))
        t1.update(at(3000.0, 0.0))
        assertEquals("cafe", (t1.update(at(250.0, 1.0)).single() as PlaceEvent.Approach).place.id)
        // 喫茶店に接近中でも、公園の内側の円に入れば公園に到着
        assertEquals("park", (t1.update(at(90.0, 2.0)).single() as PlaceEvent.Arrive).place.id)
        // 公園に到着中は、重なる喫茶店の円に入っても手放さない
        assertTrue(t1.update(at(95.0, 3.0)).isEmpty())
    }

    @Test
    fun alreadyInsideInnerCircleAtStartStays() {
        val t = PlaceTracker(listOf(park))
        assertTrue(t.update(at(30.0, 0.0)).single() is PlaceEvent.StayAtStart)
        assertTrue(t.update(at(40.0, 1.0)).isEmpty())
        assertEquals(PlaceTracker.State.ARRIVED, t.state)
    }

    @Test
    fun fastPassOnlyArrives() {
        val t = PlaceTracker(listOf(park))
        t.update(at(3000.0, 0.0))
        // 乗り物で1分に1.5km：外側の円での判定が間に合わず、いきなり内側
        val e = t.update(at(40.0, 1.0))
        assertTrue(e.single() is PlaceEvent.Arrive)
    }

    @Test
    fun nearnessForShortLocationInterval() {
        val t = PlaceTracker(listOf(park))
        assertTrue(t.isNear(at(1400.0, 0.0)))
        assertFalse(t.isNear(at(1600.0, 0.0)))
        assertEquals(1000.0, t.nearest(at(1000.0, 0.0))!!.second, 1.0)
        assertNull(PlaceTracker().nearest(at(0.0, 0.0)))
    }

    @Test
    fun deletingTrackedPlaceClearsState() {
        val t = PlaceTracker(listOf(park))
        t.update(at(3000.0, 0.0))
        t.update(at(300.0, 1.0))
        t.setPlaces(emptyList())
        assertNull(t.current)
        assertTrue(t.update(at(50.0, 2.0)).isEmpty())
    }

    @Test
    fun leaveAndEnterOnSameFixPlaysOnlyTheNewPlace() {
        val other = RegisteredPlace("o", "図書館", lat0, lng0, 500, 100, Mood.BRIGHT, 777)
        val c = Composer(1).also { it.setScene(DAY_WALK) }
        val events = compose(c, 150.0, listOf(
            80.0 to { x: Composer -> applyPlaceEvents(listOf(PlaceEvent.Approach(park)), x) },
            110.0 to { x: Composer ->
                applyPlaceEvents(listOf(PlaceEvent.Leave(park), PlaceEvent.Approach(other)), x)
            },
        ))
        // 入力は次の和音の切り替えで反映される。そこから先は新しい地点のテーマだけ
        val boundary = chordsOf(events).first { it.timeSec >= 110.0 }.timeSec
        val quiet = events.filterIsInstance<NoteEvent>()
            .filter { it.instrument == Instrument.PLUCK && it.gain == C.QUIET_PLUCK_GAIN && it.timeSec >= boundary }
        val theme = Motifs.theme(other.themeSeed)
        assertEquals(theme, quiet.take(theme.size).map { it.midi })
        assertEquals("o", c.status().place?.id)
    }
}

class StayAndPreviewTest {

    private val park = Place("p", "公園", Mood.CALM, 99)

    @Test
    fun stayAfterStartEntersStayWithoutArrivalBell() {
        val events = compose(Composer(1).also { it.setScene(DAY_WALK) }, 60.0, listOf(3.0 to { c: Composer -> c.stay(park) }))
        val chords = chordsOf(events).filter { it.timeSec >= 3.0 }.map { it.chord }
        assertEquals(cycle(Progressions.STAY, chords.size), chords)
        assertTrue(events.filterIsInstance<NoteEvent>().none { it.instrument == Instrument.BELL && it.gain == C.THEME_BELL_GAIN })
    }

    @Test
    fun themePreviewIsAudibleAndReproducible() {
        val a = ThemePreview.render(123, Mood.CALM)
        assertTrue(a.contentEquals(ThemePreview.render(123, Mood.CALM)))
        assertTrue(!a.contentEquals(ThemePreview.render(124, Mood.CALM)))
        val peak = a.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak > 1000, "試聴が小さすぎる: $peak")
        assertTrue(kotlin.math.abs(a[a.size - 2].toInt()) < 200, "最後は消えている")
    }
}

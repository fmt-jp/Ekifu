package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

/** 到着の知らせ方「読み上げ」：合図のベルを止めて間だけ残し、到着の演出に通し番号を付ける */
class ArrivalSpeechTest {

    private val park = Place("p", "公園", Mood.CALM, 99)

    @Test
    fun spokenNameUsesTheReadingWhenGiven() {
        assertEquals("こうえん", park.copy(reading = "こうえん").spokenName)
        assertEquals("公園", park.copy(reading = " ").spokenName)
        assertEquals("公園", park.spokenName)
        val r = RegisteredPlace("s", "三田", 35.0, 139.0, 500, 100, Mood.CALM, 1, reading = "みた")
        assertEquals("みた", r.place.spokenName)
    }

    @Test
    fun healingWithoutChimeKeepsTheGapButNoBell() {
        val c = Composer(1).also { it.setScene(DAY_WALK); it.setArrivalChime(false) }
        val events = compose(c, 150.0, listOf(80.0 to { x: Composer -> x.arrive(park) }))
        assertTrue(events.filterIsInstance<NoteEvent>().none { it.gain == C.ARRIVAL_CHIME_GAIN }, "合図のベルを鳴らさない")
        val theme = events.filterIsInstance<NoteEvent>().filter { it.instrument == Instrument.BELL && it.gain == C.THEME_BELL_GAIN }
        val release = events.filterIsInstance<ReleaseEvent>().filter { it.timeSec >= 80.0 }.minOf { it.timeSec }
        assertEquals(release + C.ARRIVAL_CHIME_GAP_SEC, theme.minOf { it.timeSec }, 1e-9, "曲を止める間は残す")
    }

    @Test
    fun healingArrivalSerialCountsArrivalsOnly() {
        val c = Composer(1).also { it.setScene(DAY_WALK) }
        val seen = ArrayList<Int?>()
        var chimes = 0
        fun run(from: Double, to: Double, at: Double? = null, input: ((Composer) -> Unit)? = null) {
            var t = from
            var sent = false
            while (t < to) {
                if (at != null && !sent && t >= at) { input!!(c); sent = true }
                chimes += c.composeUntil(t).filterIsInstance<NoteEvent>().count { it.gain == C.ARRIVAL_CHIME_GAIN }
                seen += c.status().arrival
                t += 0.1
            }
        }
        run(0.0, 40.0, 3.0) { it.stay(park) }
        assertTrue(seen.all { it == null }, "演出なしの滞在では番号を付けない")
        run(40.0, 50.0, 40.0) { it.leave() }
        run(50.0, 70.0, 50.0) { it.arrive(park) }
        assertTrue(1 in seen)
        // 到着中にまた到着（「着いた」ボタン）：演出をはじめからやり直し、番号が変わる
        val firstSeenAt = seen.indexOf(1)
        assertTrue(firstSeenAt >= 0)
        run(70.0, 90.0, 70.0) { it.arrive(park) }
        assertTrue(2 in seen, "2回目の到着の演出")
        assertEquals(2 * C.ARRIVAL_CHIME_MIDI.size, chimes)
    }

    @Test
    fun fusionWithoutChimeHasNoBellInTheFirstArrivalBar() {
        for (chime in listOf(true, false)) {
            val c = FusionComposer(1).also { it.setScene(DAY_WALK); it.setArrivalChime(chime) }
            val events = ArrayList<FusionEvent>()
            var arriveBlock: FusionBlock? = null
            var t = 0.0
            var sent = false
            while (t < 200.0 && arriveBlock == null) {
                val b = c.blockAt(t)
                if (b != null && b.index == 1 && !sent && t > b.startSec + 1.0) {
                    c.arrive(park)
                    sent = true
                }
                if (b?.phase == Phase.ARRIVE) arriveBlock = b
                events += c.composeUntil(t + C.COMPOSE_LOOKAHEAD_SEC)
                t += 0.05
            }
            val a = assertNotNull(arriveBlock)
            assertEquals(1, a.arrival)
            events += c.composeUntil(a.endSec)
            val chimes = events.filterIsInstance<BellNote>().filter { it.gain == F.BELL_CHIME_GAIN }
            assertEquals(if (chime) F.ARRIVAL_CHIME_MIDI.size else 0, chimes.size, "合図のベル（chime=$chime）")
            // 頭のキック・クラッシュは残す
            assertTrue(events.filterIsInstance<DrumHit>().any { it.kind == DrumKind.CRASH && kotlin.math.abs(it.timeSec - a.startSec) < 0.05 })
        }
    }
}

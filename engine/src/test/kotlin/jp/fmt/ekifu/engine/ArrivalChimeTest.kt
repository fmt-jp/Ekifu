package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.MusicConstants as C

/** 癒しの到着の合図：鳴っている音を余韻に入らせ、ベルの上りの3音。到着の和音とテーマはそのあと */
class ArrivalChimeTest {

    private val park = Place("p", "公園", Mood.CALM, 99)

    @Test
    fun healingArrivalStartsWithTheChime() {
        val c = Composer(1).also { it.setScene(DAY_WALK) }
        val events = compose(c, 150.0, listOf(80.0 to { x: Composer -> x.arrive(park) }))
        val chime = events.filterIsInstance<NoteEvent>()
            .filter { it.instrument == Instrument.BELL && it.gain == C.ARRIVAL_CHIME_GAIN }
            .sortedBy { it.timeSec }
        assertEquals(C.ARRIVAL_CHIME_MIDI.toList(), chime.map { it.midi }, "到着の合図")
        val start = chime.first().timeSec
        // 合図のときに鳴っている音を余韻に入らせる
        assertTrue(events.filterIsInstance<ReleaseEvent>().any { kotlin.math.abs(it.timeSec - start) < 1e-9 })
        // 到着の和音（ベース）とテーマのベルは合図のあと
        val afterChord = events.filterIsInstance<NoteEvent>().filter { it.instrument == Instrument.BASS && it.timeSec >= start - 1e-9 }
            .minOf { it.timeSec }
        assertEquals(start + C.ARRIVAL_CHIME_GAP_SEC, afterChord, 1e-9)
        val theme = events.filterIsInstance<NoteEvent>()
            .filter { it.instrument == Instrument.BELL && it.gain == C.THEME_BELL_GAIN }
            .sortedBy { it.timeSec }
        assertEquals(Motifs.theme(park.themeSeed), theme.map { it.midi })
        assertEquals(start + C.ARRIVAL_CHIME_GAP_SEC, theme.first().timeSec, 1e-9)
        // 合図のあいだはメロディ（プラック）を鳴らさない
        assertTrue(events.filterIsInstance<NoteEvent>().none {
            it.instrument == Instrument.PLUCK && it.timeSec > start && it.timeSec < start + C.ARRIVAL_CHIME_GAP_SEC
        })
    }

    @Test
    fun stayWithoutArrivalHasNoChime() {
        val c = Composer(1).also { it.setScene(DAY_WALK) }
        val events = compose(c, 60.0, listOf(3.0 to { x: Composer -> x.stay(park) }))
        assertTrue(events.filterIsInstance<NoteEvent>().none { it.gain == C.ARRIVAL_CHIME_GAIN })
    }
}

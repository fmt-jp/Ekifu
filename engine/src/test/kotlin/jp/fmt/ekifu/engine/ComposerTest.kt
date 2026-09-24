package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ComposerTest {

    /** 拍ごとの進み具合を与えて、指定の拍数ぶん作曲する。 */
    private fun compose(
        seed: Int,
        beats: Int,
        underground: (Long) -> Boolean = { false },
        motifAt: (Long) -> List<Int>? = { null },
        progress: (Long) -> Double,
    ): List<NoteEvent> {
        val composer = Composer(seed)
        return (0 until beats.toLong()).flatMap { b ->
            composer.composeBeat(b, BeatContext(progress(b), underground(b), motifAt(b)))
        }
    }

    private fun chordAt(events: List<NoteEvent>, beat: Long): Chord {
        val bass = events.single { it.instrument == Instrument.BASS && it.beat == beat.toDouble() }
        val rootInterval = bass.midi - C.ROOT_MIDI - C.BASS_OCTAVE_SHIFT
        return Chord.entries.single { it.intervals[0] == rootInterval }
    }

    private fun chordSequence(events: List<NoteEvent>, chords: Int) =
        (0 until chords).map { chordAt(events, it.toLong() * C.BEATS_PER_CHORD) }

    @Test
    fun sameSeedGivesSameSong() {
        val a = compose(seed = 42, beats = 400) { it / 400.0 * 1.1 }
        val b = compose(seed = 42, beats = 400) { it / 400.0 * 1.1 }
        assertEquals(a, b)
    }

    @Test
    fun differentSeedGivesDifferentSong() {
        val a = compose(seed = 1, beats = 200) { 0.5 }
        val b = compose(seed = 2, beats = 200) { 0.5 }
        assertNotEquals(a, b)
    }

    @Test
    fun departureProgressionIsIThenIV() {
        val events = compose(seed = 3, beats = 8 * 4) { 0.0 }
        assertEquals(listOf(Chord.I, Chord.IV, Chord.I, Chord.IV), chordSequence(events, 4))
    }

    @Test
    fun middleProgressionMatchesSpec() {
        val events = compose(seed = 3, beats = 8 * 9) { 0.5 }
        assertEquals(
            listOf(Chord.VI, Chord.IV, Chord.I, Chord.VSUS, Chord.II, Chord.III, Chord.IV, Chord.I, Chord.VI),
            chordSequence(events, 9),
        )
    }

    @Test
    fun preArrivalProgressionMatchesSpec() {
        val events = compose(seed = 3, beats = 8 * 5) { 0.8 }
        assertEquals(listOf(Chord.IV, Chord.VSUS, Chord.IV, Chord.I, Chord.IV), chordSequence(events, 5))
    }

    @Test
    fun phaseChangeRestartsNewProgressionAtNextChordChange() {
        // 拍 12（和音の途中）で道中に入る → 拍 16 の切り替えで道中の先頭 vi から
        val events = compose(seed = 5, beats = 8 * 4) { if (it < 12) 0.1 else 0.3 }
        assertEquals(listOf(Chord.I, Chord.IV, Chord.VI, Chord.IV), chordSequence(events, 4))
    }

    @Test
    fun padPlaysChordTonesFromRoot() {
        val events = compose(seed = 1, beats = 1) { 0.0 }
        val pads = events.filter { it.instrument == Instrument.PAD }.map { it.midi - C.ROOT_MIDI }
        assertEquals(Chord.I.intervals.toList(), pads)
    }

    @Test
    fun arrivalHoldsIAndThenStops() {
        val composer = Composer(9)
        for (b in 0 until 8L) composer.composeBeat(b, BeatContext(0.9, false))
        val arrival = composer.composeBeat(8, BeatContext(1.0, false))
        assertTrue(composer.arrived)
        assertEquals(Chord.I, composer.currentChord)
        assertTrue(arrival.all { it.instrument == Instrument.PAD || it.instrument == Instrument.BASS })
        assertTrue(arrival.all { it.durationBeats == C.ARRIVAL_HOLD_BEATS })
        assertTrue(composer.composeBeat(9, BeatContext(1.0, false)).isEmpty())
    }

    @Test
    fun melodyStaysInPentatonicRangeWithSmallLeaps() {
        val events = compose(seed = 11, beats = 2000) { 0.5 }
        val melody = events.filter { it.instrument == Instrument.PLUCK }
        assertTrue(melody.size > 500)
        for (n in melody) {
            assertTrue(n.midi in C.MELODY_MIN_MIDI..C.MELODY_MAX_MIDI)
            assertTrue(n.midi % 12 in C.PENTATONIC_PITCH_CLASSES)
        }
        melody.zipWithNext().forEach { (a, b) -> assertTrue(abs(a.midi - b.midi) <= C.MELODY_MAX_LEAP) }
    }

    @Test
    fun melodyDensityFollowsPhaseAndOffbeatFactor() {
        val beats = 20_000
        fun density(p: Double, underground: Boolean = false, offbeat: Boolean): Double {
            val events = compose(seed = 13, beats = beats, underground = { underground }) { p }
            val count = events.count { it.instrument == Instrument.PLUCK && ((it.beat % 1.0) != 0.0) == offbeat }
            return count.toDouble() / beats
        }
        assertEquals(C.MELODY_PROBABILITY_MIDDLE, density(0.5, offbeat = false), 0.02)
        assertEquals(C.MELODY_PROBABILITY_MIDDLE * C.OFFBEAT_PROBABILITY_FACTOR, density(0.5, offbeat = true), 0.02)
        assertEquals(C.MELODY_PROBABILITY_DEPARTURE, density(0.1, offbeat = false), 0.02)
        assertEquals(C.MELODY_PROBABILITY_PRE_ARRIVAL, density(0.8, offbeat = false), 0.02)
        assertEquals(
            C.MELODY_PROBABILITY_MIDDLE * C.UNDERGROUND_PROBABILITY_FACTOR,
            density(0.5, underground = true, offbeat = false),
            0.02,
        )
    }

    @Test
    fun undergroundLowersChordsAndMelodyByAnOctave() {
        val above = compose(seed = 4, beats = 1) { 0.0 }
        val below = compose(seed = 4, beats = 1, underground = { true }) { 0.0 }
        val abovePad = above.filter { it.instrument == Instrument.PAD }.map { it.midi }
        val belowPad = below.filter { it.instrument == Instrument.PAD }.map { it.midi }
        assertEquals(abovePad.map { it + C.UNDERGROUND_OCTAVE_SHIFT }, belowPad)
    }

    @Test
    fun stationPassRingsBellsHalfBeatApartAndMiddleReprisesIt() {
        val motif = listOf(69, 72, 74)
        val events = compose(seed = 6, beats = 60, motifAt = { if (it == 3L) motif else null }) { 0.5 }
        val bells = events.filter { it.instrument == Instrument.BELL }
        assertEquals(motif, bells.map { it.midi })
        assertEquals(listOf(3.0, 3.5, 4.0), bells.map { it.beat })

        // 24 拍ごとに、直前の駅のモチーフを静かにプラックで
        for (reprise in listOf(24.0, 48.0)) {
            val notes = events.filter {
                it.instrument == Instrument.PLUCK && it.velocity == C.MOTIF_REPRISE_VELOCITY &&
                    it.beat >= reprise && it.beat < reprise + 2
            }
            assertEquals(motif, notes.map { it.midi })
        }
    }
}

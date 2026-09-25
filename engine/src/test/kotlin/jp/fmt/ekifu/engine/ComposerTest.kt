package jp.fmt.ekifu.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.MusicConstants as C

class ComposerTest {

    private val park = Place("p", "公園", Mood.CALM, themeSeed = 99)

    private fun newComposer(interludeSec: Double = C.INTERLUDE_EVERY_SEC) =
        Composer(seed = 1, config = ComposerConfig(interludeEverySec = interludeSec)).also { it.setScene(DAY_WALK) }

    @Test
    fun sameSeedAndInputsGiveSameEvents() {
        val inputs = listOf<Pair<Double, (Composer) -> Unit>>(
            100.0 to { it.approach(park) },
            140.0 to { it.arrive(park) },
            200.0 to { it.leave() },
        )
        val a = compose(newComposer(), 300.0, inputs)
        val b = compose(newComposer(), 300.0, inputs)
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun startPhaseAlternatesIandIVForOneMinuteThenJourney() {
        val chords = chordsOf(compose(newComposer(), 150.0))
        val start = chords.filter { it.timeSec < C.START_PHASE_SEC }
        assertEquals(start.map { it.chord }, start.indices.map { if (it % 2 == 0) Chord.I else Chord.IV })

        val journey = chords.filter { it.timeSec >= C.START_PHASE_SEC }.map { it.chord }
        assertEquals(cycle(Progressions.JOURNEY, journey.size), journey)
    }

    @Test
    fun chordsChangeEveryEightBeats() {
        val chords = chordsOf(compose(newComposer(), 60.0))
        chords.zipWithNext().forEach { (a, b) ->
            assertEquals(C.CHORD_BEATS * C.WALK_BEAT_SEC, b.timeSec - a.timeSec, 1e-6)
        }
    }

    @Test
    fun interludeComesAfterJourneyContinuesAndReturns() {
        val chords = chordsOf(compose(newComposer(interludeSec = 120.0), 400.0))
        val journeyStart = chords.first { it.timeSec >= C.START_PHASE_SEC }.timeSec
        val afterInterlude = chords.filter { it.timeSec >= journeyStart + 120.0 }.map { it.chord }
        assertEquals(Progressions.INTERLUDE, afterInterlude.take(4))
        assertEquals(Progressions.JOURNEY.take(3), afterInterlude.drop(4).take(3))
    }

    @Test
    fun approachRepeatsProgressionAndPlaysThemeQuietly() {
        val events = compose(newComposer(), 200.0, listOf(100.0 to { c: Composer -> c.approach(park) }))
        val chords = chordsOf(events).filter { it.timeSec >= 100.0 }
        assertEquals(cycle(Progressions.APPROACH, chords.size), chords.map { it.chord })
        // 8拍ごとにテーマをプラックで小さく
        val theme = Motifs.theme(park.themeSeed)
        val quiet = events.filterIsInstance<NoteEvent>()
            .filter { it.instrument == Instrument.PLUCK && it.gain == C.QUIET_PLUCK_GAIN && it.timeSec >= 100.0 }
        assertEquals(chords.size * theme.size, quiet.size)
        assertEquals(theme, quiet.take(theme.size).map { it.midi })
    }

    @Test
    fun arriveLandsOnLongIWithBellThemeThenStays() {
        val events = compose(
            newComposer(), 250.0,
            listOf(100.0 to { c: Composer -> c.approach(park) }, 130.0 to { c: Composer -> c.arrive(park) }),
        )
        val chords = chordsOf(events).filter { it.timeSec >= 130.0 }
        assertEquals(Chord.I, chords[0].chord)
        // I を16拍（落ち着く：1拍1.0秒に向かって移行中なので 0.9〜1.0秒）
        val arriveLen = chords[1].timeSec - chords[0].timeSec
        assertTrue(arriveLen > C.ARRIVE_CHORD_BEATS * 0.9 && arriveLen <= C.ARRIVE_CHORD_BEATS * 1.0 + 1e-6)
        assertEquals(Progressions.STAY, chords.drop(1).take(4).map { it.chord })

        val bells = events.filterIsInstance<NoteEvent>()
            .filter { it.instrument == Instrument.BELL && it.gain == C.THEME_BELL_GAIN }
        assertEquals(Motifs.theme(park.themeSeed), bells.map { it.midi })
        assertEquals(chords[0].timeSec, bells.first().timeSec, 1e-9)
    }

    @Test
    fun nostalgicPlaceUsesItsOwnStayProgression() {
        val old = park.copy(mood = Mood.NOSTALGIC)
        val events = compose(newComposer(), 200.0, listOf(100.0 to { c: Composer -> c.arrive(old) }))
        val chords = chordsOf(events).filter { it.timeSec >= 100.0 }.map { it.chord }
        assertEquals(Progressions.STAY_NOSTALGIC, chords.drop(1).take(4))
        val fb = events.filterIsInstance<ControlEvent>().last().delayFeedback
        assertEquals(C.NOSTALGIC_DELAY_FEEDBACK, fb)
    }

    @Test
    fun leavingPlaysThemeOnceAndReturnsToJourney() {
        val events = compose(
            newComposer(), 260.0,
            listOf(100.0 to { c: Composer -> c.arrive(park) }, 160.0 to { c: Composer -> c.leave() }),
        )
        val chords = chordsOf(events).filter { it.timeSec >= 160.0 }
        assertEquals(Progressions.JOURNEY.take(3), chords.take(3).map { it.chord })
        val theme = Motifs.theme(park.themeSeed)
        val quiet = events.filterIsInstance<NoteEvent>()
            .filter { it.instrument == Instrument.PLUCK && it.gain == C.QUIET_PLUCK_GAIN && it.timeSec >= 160.0 }
        assertEquals(theme, quiet.take(theme.size).map { it.midi })
        // 道中に戻った後、24拍ごとの再現までは同じテーマを繰り返さない
        assertTrue(quiet.none { it.timeSec in chords[1].timeSec..chords[2].timeSec })
    }

    @Test
    fun startInStaySkipsStartPhase() {
        val c = newComposer()
        c.startInStay(park)
        val chords = chordsOf(compose(c, 40.0)).map { it.chord }
        assertEquals(Progressions.STAY, chords.take(4))
    }

    @Test
    fun sceneChangePlaysNewPlaceMotifWithBell() {
        val next = Scene("other1", Speed.VEHICLE, Familiarity.NORMAL, SunLevel.DAY)
        val events = compose(newComposer(), 120.0, listOf(80.0 to { c: Composer -> c.setScene(next) }))
        val boundary = chordsOf(events).first { it.timeSec >= 80.0 }.timeSec
        val bells = events.filterIsInstance<NoteEvent>()
            .filter { it.instrument == Instrument.BELL && abs(it.timeSec - boundary) < 3.0 && it.timeSec >= boundary }
        assertEquals(Motifs.placeMotif("other1"), bells.map { it.midi })
    }

    @Test
    fun sameGridSceneChangeDoesNotRingBell() {
        val slower = DAY_WALK.copy(speed = Speed.STILL, sun = SunLevel.TWILIGHT)
        val events = compose(newComposer(), 150.0, listOf(80.0 to { c: Composer -> c.setScene(slower) }))
        assertTrue(events.filterIsInstance<NoteEvent>().none { it.instrument == Instrument.BELL && it.timeSec > 1.0 })
        // 値（全体ローパス）は移る
        assertEquals(C.TWILIGHT_CUTOFF_HZ, events.filterIsInstance<ControlEvent>().last().masterCutoffHz)
    }

    @Test
    fun motifAtStartCanBeSkipped() {
        val c = Composer(1, ComposerConfig(playMotifAtStart = false)).also { it.setScene(DAY_WALK) }
        assertTrue(compose(c, 10.0).filterIsInstance<NoteEvent>().none { it.instrument == Instrument.BELL })
        val d = Composer(1).also { it.setScene(DAY_WALK) }
        assertEquals(
            Motifs.placeMotif(DAY_WALK.gridId),
            compose(d, 10.0).filterIsInstance<NoteEvent>().filter { it.instrument == Instrument.BELL }.map { it.midi },
        )
    }

    @Test
    fun tempoMovesGraduallyOverEightBeats() {
        val c = newComposer()
        val still = DAY_WALK.copy(speed = Speed.STILL)
        val chords = chordsOf(compose(c, 200.0, listOf(80.0 to { x: Composer -> x.setScene(still) })))
        val after = chords.filter { it.timeSec >= 80.0 }
        val firstLen = after[1].timeSec - after[0].timeSec
        val secondLen = after[2].timeSec - after[1].timeSec
        // 1拍0.9秒 → 1.1秒へ8拍かけて移る
        assertTrue(firstLen > 8 * 0.9 && firstLen < 8 * 1.1, "移行中の長さ: $firstLen")
        assertEquals(8 * 1.1, secondLen, 1e-6)
    }

    @Test
    fun nightLowersChordsByAnOctave() {
        val c = Composer(1).also { it.setScene(DAY_WALK.copy(sun = SunLevel.NIGHT)) }
        val bass = compose(c, 5.0).filterIsInstance<NoteEvent>().first { it.instrument == Instrument.BASS }
        assertEquals(C.ROOT_MIDI - 24, bass.midi)
        val control = compose(Composer(1).also { it.setScene(DAY_WALK.copy(sun = SunLevel.NIGHT)) }, 1.0)
            .filterIsInstance<ControlEvent>().first()
        assertEquals(C.NIGHT_CUTOFF_HZ, control.masterCutoffHz)
    }

    @Test
    fun melodyDensityFollowsNoteProbability() {
        fun count(speed: Speed): Double {
            val c = Composer(3, ComposerConfig(startPhaseSec = 0.0)).also { it.setScene(DAY_WALK.copy(speed = speed)) }
            val beats = 2000.0 / speed.beatSec
            val melody = compose(c, 2000.0).filterIsInstance<NoteEvent>()
                .filter { it.instrument == Instrument.PLUCK && it.gain != C.QUIET_PLUCK_GAIN }
            return melody.size / beats
        }
        // 1拍あたり：頭 p ＋ 裏 0.6p（モチーフ中は鳴らさないので少し下回る）
        val walk = count(Speed.WALK)
        val vehicle = count(Speed.VEHICLE)
        assertTrue(walk in 0.4 * 1.6 * 0.8..0.4 * 1.6 * 1.05, "徒歩: $walk")
        assertTrue(vehicle in 0.55 * 1.6 * 0.8..0.55 * 1.6 * 1.05, "乗り物: $vehicle")
    }

    @Test
    fun stopPlaysIAndFadesOut() {
        val c = newComposer()
        compose(c, 30.0)
        val events = c.stop(30.0)
        assertNotNull(events.filterIsInstance<FadeOutEvent>().singleOrNull())
        assertEquals(Chord.I, chordsOf(events).single().chord)
        assertEquals(30.0 + C.ENDING_FADE_SEC, c.endTimeSec)
        assertTrue(c.composeUntil(100.0).isEmpty())
        assertEquals(Phase.ENDING, c.status().phase)
    }
}

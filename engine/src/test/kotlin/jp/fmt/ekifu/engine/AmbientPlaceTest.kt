package jp.fmt.ekifu.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.AmbientConstants as A

/** 曲調「アンビエント」段階8b：曲の組み立てと登録地点の演出（判定にはすぐ反応する） */
class AmbientPlaceTest {

    private val park = Place("park", "公園", Mood.CALM, 12345)
    private val walkDay = Scene("xn76ur", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY)

    private class Run(val events: List<AmbientEvent>, val chords: List<AmbientComposer.ChordInfo>)

    /** エンジンと同じく、入力のあとに react(今の時刻) を呼ぶ */
    private fun run(
        sec: Double,
        inputs: List<Pair<Double, (ComposerInput) -> Unit>> = emptyList(),
        config: AmbientComposerConfig = AmbientComposerConfig(),
        chime: Boolean = true,
        scene: Scene = walkDay,
    ): Run {
        val c = AmbientComposer(5, config).also { it.setScene(scene); it.setArrivalChime(chime) }
        val events = ArrayList<AmbientEvent>()
        val chords = LinkedHashMap<Double, AmbientComposer.ChordInfo>()
        var next = 0
        var t = 0.0
        while (t < sec) {
            while (next < inputs.size && inputs[next].first <= t) {
                inputs[next++].second(c)
                c.react(t)
            }
            events += c.composeUntil(t + MusicConstants.COMPOSE_LOOKAHEAD_SEC)
            c.chordAt(t)?.let { chords[it.startSec] = it }
            t += 0.1
        }
        return Run(events, chords.values.toList())
    }

    private fun inChord(r: Run, ch: AmbientComposer.ChordInfo) =
        r.events.filter { it.timeSec >= ch.startSec - 1e-6 && it.timeSec < ch.startSec + ch.durSec - 1e-6 }

    @Test
    fun startIsDroneOnlyThenPadsAndGrains() {
        val r = run(40.0)
        val start = r.chords.first()
        assertEquals(Phase.START, start.phase)
        val ev = inChord(r, start)
        assertTrue(ev.any { it is AmbDrone })
        assertTrue(ev.none { it is AmbPad || it is AmbGrain || it is AmbPiano })
        val second = r.chords[1]
        assertEquals(Phase.JOURNEY, second.phase)
        assertTrue(inChord(r, second).any { it is AmbPad })
    }

    @Test
    fun interludeIsDroneAndGrainsOnly() {
        val r = run(200.0, config = AmbientComposerConfig(interludeEverySec = 40.0))
        val inter = r.chords.filter { it.phase == Phase.INTERLUDE }
        assertTrue(inter.size >= A.INTERLUDE_CHORDS)
        inter.take(A.INTERLUDE_CHORDS).forEach { ch -> assertTrue(inChord(r, ch).none { it is AmbPad || it is AmbPiano }) }
        assertEquals(Phase.JOURNEY, r.chords[r.chords.indexOf(inter[A.INTERLUDE_CHORDS - 1]) + 1].phase)
    }

    @Test
    fun gridChangeRingsTheMotifOnTheBell() {
        val r = run(80.0, listOf(30.0 to { c -> c.setScene(walkDay.copy(gridId = "xn7e2t")) }))
        val after = r.chords.first { it.scene.gridId == "xn7e2t" }
        assertEquals(Motifs.placeMotif("xn7e2t"), inChord(r, after).filterIsInstance<AmbBell>().map { it.midi })
    }

    @Test
    fun approachReactsWithinSecondsAndComesCloserEachChord() {
        val r = run(120.0, listOf(31.0 to { c -> c.approach(park) }))
        val approach = r.chords.filter { it.phase == Phase.APPROACH }
        assertTrue(approach.first().startSec <= 31.0 + A.REACT_SEC + 0.11, "すぐ反応（${approach.first().startSec}）")
        // 鳴っていた和音は切り上げる
        assertTrue(r.events.any { it is AmbCut && it.timeSec == approach.first().startSec })
        val theme = Motifs.theme(park.themeSeed)
        val gains = approach.take(4).map { ch ->
            val bells = inChord(r, ch).filterIsInstance<AmbBell>()
            assertEquals(theme, bells.map { it.midi })
            bells.first()
        }
        // 遠く（小さく・残響多め）からだんだん近く
        assertTrue(gains.zipWithNext().all { (a, b) -> b.gain >= a.gain })
        assertEquals(A.BELL_FAR_SEND, gains.first().send)
        assertEquals(A.BELL_SEND, gains.last().send)
        // 途中からピアノも重なる
        assertTrue(inChord(r, approach[0]).none { it is AmbPiano })
        assertEquals(theme.size, inChord(r, approach[A.APPROACH_PIANO_FROM]).count { it is AmbPiano })
    }

    @Test
    fun arrivalCueThemeLandingThenStay() {
        for (chime in listOf(true, false)) {
            val r = run(160.0, listOf(31.0 to { c -> c.arrive(park) }), chime = chime)
            val arrive = r.chords.filter { it.phase == Phase.ARRIVE }
            assertEquals(3, arrive.size)
            assertTrue(arrive[0].startSec <= 31.0 + A.REACT_SEC + 0.11)
            assertEquals(1, arrive[0].arrival)
            assertEquals(park, arrive[0].place)
            // 合図：パッドなし、ベルの F・A・C（読み上げだけならなし）
            val cue = inChord(r, arrive[0])
            assertTrue(cue.none { it is AmbPad })
            assertTrue(cue.any { it is AmbCut })
            assertEquals(if (chime) A.ARRIVAL_CHIME_MIDI.toList() else emptyList(), cue.filterIsInstance<AmbBell>().map { it.midi })
            assertEquals(A.ARRIVE_CUE_SEC, arrive[0].durSec, 1e-9)
            // テーマ：ピアノとベル
            val theme = Motifs.theme(park.themeSeed)
            val t = inChord(r, arrive[1])
            assertEquals(theme, t.filterIsInstance<AmbBell>().map { it.midi })
            assertEquals(theme.size, t.count { it is AmbPiano })
            // 着地：Fadd9 を大きく（音を足して）長く
            assertEquals(AmbChord.FADD9, arrive[2].chord)
            assertTrue(inChord(r, arrive[2]).count { it is AmbPad } > AmbChord.FADD9.tones.size)
            assertTrue(arrive[2].durSec > A.chordSec(Speed.WALK))
            // 滞在：主和音のまわり、到着の番号はない
            val stay = r.chords.filter { it.phase == Phase.STAY }
            assertTrue(stay.isNotEmpty())
            assertTrue(stay.all { it.arrival == null && it.chord in AmbientHarmony.STAY })
        }
    }

    @Test
    fun stayBellsSometimesButNotRightAfterArrival() {
        val r = run(260.0, listOf(10.0 to { c -> c.stay(park) }))
        val stay = r.chords.filter { it.phase == Phase.STAY }
        assertTrue(stay.size >= A.STAY_BELL_EVERY_CHORDS + 1)
        assertTrue(inChord(r, stay[0]).none { it is AmbBell })
        assertEquals(A.STAY_BELL_GAIN, inChord(r, stay[A.STAY_BELL_EVERY_CHORDS - 1]).filterIsInstance<AmbBell>().first().gain, 1e-9)
        assertTrue(r.events.filterIsInstance<AmbBell>().none { it.gain == A.ARRIVAL_CHIME_GAIN }, "演出なしの滞在で合図")
        assertTrue(r.chords.all { it.arrival == null })
    }

    @Test
    fun leavingFadesTheThemeAway() {
        val r = run(160.0, listOf(10.0 to { c -> c.stay(park) }, 60.0 to { c -> c.leave() }))
        val leave = r.chords.first { it.startSec > 60.0 && it.phase == Phase.JOURNEY }
        assertTrue(leave.startSec <= 60.0 + A.REACT_SEC + 0.11)
        assertNull(leave.place)
        val bells = inChord(r, leave).filterIsInstance<AmbBell>()
        assertEquals(Motifs.theme(park.themeSeed), bells.map { it.midi })
        assertTrue(bells.zipWithNext().all { (a, b) -> b.gain < a.gain }, "だんだん小さく")
        // 次の和音ではもう鳴らさない
        val next = r.chords[r.chords.indexOf(leave) + 1]
        assertTrue(inChord(r, next).filterIsInstance<AmbBell>().none { it.send == A.BELL_FAR_SEND })
    }

    @Test
    fun approachAfterStartGoesStraightToApproach() {
        val r = run(60.0, listOf(0.0 to { c -> c.approachAfterStart(park) }))
        assertEquals(Phase.START, r.chords[0].phase)
        assertEquals(Phase.APPROACH, r.chords[1].phase)
    }

    @Test
    fun cutDropsTheRestOfTheCurrentChord() {
        val s = AmbientSynth(8000)
        s.schedule(listOf(AmbPad(0.0, 60, 20.0, 1.0, 1000.0, 0.0), AmbGrain(5.0, 80, 0.1, 0.0)))
        s.schedule(listOf(AmbCut(2.0)))
        val out = ShortArray(8000 * 2 * 4)
        s.render(out, 8000 * 4, 0)
        // 5秒の粒は取り消された（4秒で鳴っているのはパッドの余韻だけ）
        assertTrue(s.activeVoices <= 1)
    }

    @Test
    fun demoEngineFollowsTheCommuteScript() {
        val e = AmbientEngine.demo(null, 0.0, 8000)
        val phases = HashSet<Phase>()
        val buf = ShortArray(8000 * 2)
        repeat(DemoScript.COMMUTE.cues.last().atSec.toInt() + 90) {
            e.render(buf, 8000, 0)
            phases += e.status().composer.phase
        }
        for (p in listOf(Phase.START, Phase.JOURNEY, Phase.APPROACH, Phase.ARRIVE, Phase.STAY)) assertTrue(p in phases, "$p がない")
    }
}

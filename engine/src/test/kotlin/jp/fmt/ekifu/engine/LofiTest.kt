package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.BandSounds
import jp.fmt.ekifu.engine.dsp.LofiFx
import jp.fmt.ekifu.engine.sf2.SampleBank
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.LofiConstants as L
import jp.fmt.ekifu.engine.MusicConstants as C

/** 曲調「ローファイ」段階7a：楽器・加工・伴奏とビート */
class LofiTest {

    private val sr = C.SAMPLE_RATE

    private val sounds: BandSounds by lazy {
        val f = listOf(File("../app/src/main/assets/lofi.ekb"), File("app/src/main/assets/lofi.ekb")).first { it.exists() }
        BandSounds(f.inputStream().use { SampleBank.read(it) }, L.KIT)
    }

    private fun scene(speed: Speed = Speed.WALK, f: Familiarity = Familiarity.NORMAL, sun: SunLevel = SunLevel.DAY) =
        Scene("xn76ur", speed, f, sun)

    private fun compose(c: LofiComposer, sec: Double): List<FusionEvent> {
        val events = ArrayList<FusionEvent>()
        var t = 0.0
        while (t < sec) {
            events += c.composeUntil(t)
            t += 0.25
        }
        return events
    }

    @Test
    fun bankHasTheInstruments() {
        assertNotNull(sounds.lead)
        for ((kind, key) in L.KIT.drumKeys) assertTrue(sounds.drums.zonesFor(key, 100).isNotEmpty(), "ドラム $kind（$key）がない")
        for (k in L.BASS_MIN_MIDI..L.BASS_MAX_MIDI + 7) assertTrue(sounds.bass.zones.any { k in it.keyLo..it.keyHi }, "ベースの $k がない")
        for (k in L.KEYS_MIN_MIDI..L.KEYS_MAX_MIDI) assertTrue(sounds.keys.zones.any { k in it.keyLo..it.keyHi }, "鍵盤の $k がない")
    }

    @Test
    fun tempoFollowsSpeed() {
        for (speed in Speed.entries) {
            val c = LofiComposer(1).also { it.setScene(scene(speed)) }
            compose(c, 30.0)
            val bars = (0 until 4).mapNotNull { c.barAt(20.0 + it) }
            val stepSec = bars.last().stepSec
            assertEquals(60.0 / L.bpm(speed) / 4, stepSec, 1e-9, "$speed のテンポ")
        }
        assertTrue(L.bpm(Speed.STILL) < L.bpm(Speed.WALK) && L.bpm(Speed.WALK) < L.bpm(Speed.VEHICLE))
    }

    @Test
    fun beatHasBackbeatSwingAndBass() {
        val c = LofiComposer(3).also { it.setScene(scene()) }
        val events = compose(c, 60.0)
        val bar = c.barAt(30.0)!!
        val inBar = events.filter { it.timeSec >= bar.startSec - 0.05 && it.timeSec < bar.startSec + bar.stepSec * 16 - 0.05 }
        val snares = inBar.filterIsInstance<DrumHit>().filter { it.kind == DrumKind.SNARE && it.velocity > 0.5 }
        assertEquals(2, snares.size, "2拍目と4拍目のスネア")
        // スネアは拍より少し後ろ（もたつき）
        for ((s, step) in snares.sortedBy { it.timeSec }.zip(listOf(4, 12))) {
            assertTrue(s.timeSec > bar.startSec + step * bar.stepSec, "スネアがもたつかない")
        }
        // 8分の裏のハイハットは跳ねる（ちょうど真ん中より後ろ）
        val hats = inBar.filterIsInstance<DrumHit>().filter { it.kind == DrumKind.HAT }
        val offbeat = hats.filter { abs(((it.timeSec - bar.startSec) / bar.stepSec) - 2) < 0.6 }
        assertTrue(offbeat.isNotEmpty() && offbeat.all { it.timeSec > bar.startSec + 2 * bar.stepSec }, "跳ねない")
        // ベースは小節の頭で根音（音域内）
        val bass = inBar.filterIsInstance<PolyNote>().filter { it.part == PolyPart.BASS }.minBy { it.timeSec }
        assertEquals(bar.chord.root, bass.midi % 12)
        assertTrue(bass.midi in L.BASS_MIN_MIDI..L.BASS_MAX_MIDI)
        // 和音はルートを抜いた音で、音域内
        val keys = inBar.filterIsInstance<PolyNote>().filter { it.part == PolyPart.BRASS }
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.all { it.midi in L.KEYS_MIN_MIDI..L.KEYS_MAX_MIDI })
        assertTrue(keys.all { (it.midi - bar.chord.root) % 12 != 0 || bar.chord.tones.any { t -> t % 12 == 0 } })
    }

    @Test
    fun hatsAreDenserOnVehiclesAndSparserWhenStill() {
        fun hatsPerBar(speed: Speed): Double {
            val c = LofiComposer(5).also { it.setScene(scene(speed)) }
            val events = compose(c, 40.0)
            val bars = (0 until 6).map { c.barAt(20.0 + it * 3.5)!! }.distinct()
            val n = bars.sumOf { b ->
                events.count { it is DrumHit && (it.kind == DrumKind.HAT || it.kind == DrumKind.OPEN_HAT) && it.timeSec >= b.startSec - 0.02 && it.timeSec < b.startSec + b.stepSec * 16 - 0.02 }
            }
            return n.toDouble() / bars.size
        }
        val still = hatsPerBar(Speed.STILL)
        val walk = hatsPerBar(Speed.WALK)
        val vehicle = hatsPerBar(Speed.VEHICLE)
        assertTrue(still < walk && walk < vehicle, "静止 $still・歩き $walk・乗り物 $vehicle")
    }

    @Test
    fun sunSetsCutoffAndNoise() {
        assertTrue(L.cutoffHz(SunLevel.NIGHT) < L.cutoffHz(SunLevel.TWILIGHT) && L.cutoffHz(SunLevel.TWILIGHT) < L.cutoffHz(SunLevel.DAY))
        val c = LofiComposer(1).also { it.setScene(scene(sun = SunLevel.NIGHT)) }
        val events = compose(c, 30.0)
        // 始まりはこもらせ、道中から太陽のこもり具合
        assertEquals(L.MUFFLED_CUTOFF_HZ, events.filterIsInstance<FusionControl>().first().masterCutoffHz)
        assertEquals(L.cutoffHz(SunLevel.NIGHT), events.filterIsInstance<FusionControl>().last().masterCutoffHz)
        assertEquals(L.noiseFactor(SunLevel.NIGHT), events.filterIsInstance<LofiControl>().first().noiseFactor)
    }

    @Test
    fun familiarityPicksTheProgressions() {
        for (f in Familiarity.entries) {
            val c = LofiComposer(7).also { it.setScene(scene(f = f)) }
            compose(c, 60.0)
            val allowed = LofiHarmony.progressions(f).flatten().toSet()
            val used = (0 until 10).mapNotNull { c.barAt(20.0 + it * 3.5)?.chord }.toSet()
            assertTrue(allowed.containsAll(used), "$f：$used")
        }
    }

    @Test
    fun sameSeedSameEventsAndCopyContinuesTheSame() {
        val a = LofiComposer(9).also { it.setScene(scene()) }
        val b = LofiComposer(9).also { it.setScene(scene()) }
        assertEquals(compose(a, 30.0), compose(b, 30.0))
        val c = a.copy()
        assertEquals(a.composeUntil(60.0), c.composeUntil(60.0))
    }

    @Test
    fun engineIsDeterministicAcrossChunkingAndCopies() {
        val sec = 6.0
        val n = (sec * sr).toInt()
        val whole = ShortArray(n * 2)
        LofiEngine(11, sounds, sampleRate = sr).render(whole, n, 0)
        val parts = ShortArray(n * 2)
        val e = LofiEngine(11, sounds, sampleRate = sr)
        var done = 0
        var copy: SoundEngine? = null
        while (done < n) {
            val k = minOf(1500 + done % 700, n - done)
            if (done > n / 2 && copy == null) copy = e.copy()
            (copy ?: e).render(parts, k, done)
            done += k
        }
        assertContentEquals(whole, parts)
    }

    @Test
    fun soundsAtAReasonableLevelWithoutClipping() {
        val sec = 20.0
        val n = (sec * sr).toInt()
        val out = ShortArray(n * 2)
        LofiEngine.demo(sounds, 0.0, sr).render(out, n, 0)
        val clipped = out.count { abs(it.toInt()) >= 32767 }
        val rms = sqrt(out.sumOf { (it / 32768.0) * (it / 32768.0) } / out.size)
        assertTrue(clipped < out.size / 2000, "音割れ $clipped")
        assertTrue(rms in 0.03..0.35, "音量 $rms")
    }

    @Test
    fun lofiFxWobblesAndAddsNoiseButCopiesExactly() {
        val fx = LofiFx(sr, 1)
        val outs = ArrayList<Double>()
        repeat(sr) { fx.process(0.0, 0.0); outs += fx.outL }
        // 無音でもノイズ（ヒス）は少し鳴る
        assertTrue(outs.any { it != 0.0 })
        assertTrue(outs.all { abs(it) < 0.2 })
        val a = fx.copy()
        val b = fx.copy()
        repeat(1000) { i ->
            val x = kotlin.math.sin(i * 0.05)
            a.process(x, x); b.process(x, x)
            assertEquals(a.outL, b.outL)
        }
        // 夜はノイズが多い
        fun noiseRms(factor: Double): Double {
            val f = LofiFx(sr, 2).also { it.noiseFactor = factor }
            var s = 0.0
            repeat(sr * 2) { f.process(0.0, 0.0); s += f.outL * f.outL }
            return sqrt(s / (sr * 2))
        }
        assertTrue(noiseRms(L.noiseFactor(SunLevel.NIGHT)) > noiseRms(L.noiseFactor(SunLevel.DAY)))
    }

    @Test
    fun bandKitStillHasItsFourInstruments() {
        assertEquals(4, BandConstants.KIT.picks.size)
        assertEquals(4, L.KIT.picks.size)
    }
}
